package com.ociweb.jfast.catalog.extraction;

import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.ociweb.jfast.catalog.loader.FieldReferenceOffsetManager;
import com.ociweb.jfast.catalog.loader.TemplateCatalogConfig;
import com.ociweb.jfast.stream.FASTRingBuffer;
import com.ociweb.jfast.stream.FASTRingBufferWriter;

public class StreamingVisitor implements ExtractionVisitor {

    public static final int CATALOG_TEMPLATE_ID = 0;
    
    RecordFieldExtractor messageTypes;   
    
    byte[] catBytes;
    TemplateCatalogConfig catalog;
    
    long beforeDotValue;
    int beforeDotValueChars;
    long accumValue;
    int accumValueChars;
    long accumSign;
    boolean aftetDot;
    boolean startingMessage = true;
    
    
    static final long[] POW_10;
    
    static {
    	int max = 19;
    	POW_10 = new long[max];
    	int j = 0;
    	long total=1;
    	while (j<max) {
    		POW_10[j++]=total;
    		total *= 10l;
    	}    	
    	//System.err.println(Arrays.toString(POW_10));
    }
    
    
    //chars are written to  ring buffer.
    
    int bytePosActive;
    int bytePosStartField;
    
    FASTRingBuffer ringBuffer;
    
    public StreamingVisitor(RecordFieldExtractor messageTypes) {
        
    	//initial ring buffer needed only for the first catalog then we move on from this one.
    	byte[] catBytes = messageTypes.getCatBytes(); //must use a ring buffer from catalog or it will not be initilzied for use.
    	ringBuffer = (new TemplateCatalogConfig(catBytes)).ringBuffers().buffers[0];
    			 
    	ringBufferList.add(ringBuffer);
    	
        this.messageTypes = messageTypes;    
        
        messageTypes.restToRecordStart();
        
        aftetDot = false;
        beforeDotValue = 0;
        beforeDotValueChars = 0;
        
        accumSign = 1;
        
        accumValue = 0;
        accumValueChars = 0;
        
    }
        
    
    @Override
    public void appendContent(MappedByteBuffer mappedBuffer, int pos, int limit, boolean contentQuoted) {
                
    	
    	
        //discovering the field types using the same way the previous visitor did it
        messageTypes.appendContent(mappedBuffer, pos, limit, contentQuoted);
                  
        //keep bytes here in case we need it, will only be known after we are done
        int p = pos;
        while (p<limit) {
            byte b = mappedBuffer.get(p);
            ringBuffer.byteBuffer[ringBuffer.byteMask&bytePosActive++] = b; //TODO: need to check for the right stop point
                        
            if ('.' == b) {
                aftetDot = true;
                beforeDotValue = accumValue;
                beforeDotValueChars = accumValueChars;
                accumValue = 0;
                accumValueChars = 0;
            } else {
               if ('+' != b) {
                   if ('-' == b) { 
                       accumSign = -1;
                   } else {
                       int v = (b-'0');
                       accumValue = (10*accumValue) + v;    
                       accumValueChars++;
                   }                    
               }
            }
            p++;
        }                 
        
    }

    @Override
    public void closeRecord(int startPos) {
        
        messageTypes.restToRecordStart();
        
        //move the pointer up to the next record
        bytePosStartField = ringBuffer.addBytePos.value = bytePosActive;
        
        FASTRingBuffer.publishWrites(ringBuffer.headPos, ringBuffer.workingHeadPos);
        startingMessage = true;        
    }

    @Override
    public void closeField() {
    	
    	if (startingMessage) {
    		 int templateId = 1;
    		 FASTRingBufferWriter.writeInt(ringBuffer, templateId);  
    	}    	
    	
        //selecting the message type one field at at time as we move forward
        int fieldType = messageTypes.convertRawTypeToSpecific(messageTypes.moveNextField());
        
        switch (fieldType) {
            case RecordFieldExtractor.TYPE_NULL:
                //TODO: what optional types are available? what if there are two then follow the order.
                System.err.println("need to find a null and we have many to choose from, now what");                
                break;
            case RecordFieldExtractor.TYPE_UINT:                
                FASTRingBufferWriter.writeInt(ringBuffer, (int)(accumValue*accumSign));  
                break;            
            case RecordFieldExtractor.TYPE_SINT:
                FASTRingBufferWriter.writeInt(ringBuffer, (int)(accumValue*accumSign));  
                break;    
            case RecordFieldExtractor.TYPE_ULONG:
                FASTRingBufferWriter.writeLong(ringBuffer, accumValue*accumSign);  
                break;    
            case RecordFieldExtractor.TYPE_SLONG:
                FASTRingBufferWriter.writeLong(ringBuffer, accumValue*accumSign);  
                break;    
            case RecordFieldExtractor.TYPE_ASCII:
                FASTRingBufferWriter.finishWriteBytes(ringBuffer, bytePosActive-bytePosStartField);
                break;
            case RecordFieldExtractor.TYPE_BYTES:                
                FASTRingBufferWriter.finishWriteBytes(ringBuffer, bytePosActive-bytePosStartField);
                break;
            case RecordFieldExtractor.TYPE_DECIMAL:
                int exponent = messageTypes.globalExponent(); //always positive, positions measured after the dot
                                
                //this is A solution but it is not THE global solution
                long totalValue = (beforeDotValue*POW_10[accumValueChars])+accumValue;
                int  totalExp = accumValueChars;
                
                //if totalExp is larger than const exponent must /10 to total value and dec by that many
                while (totalExp>exponent) {
                	totalValue=totalValue/10;
                	totalExp--;
                }
                
                //if totalExp is smaller than const exponent must *10 to total value and inc by that many
                while (totalExp<exponent) {
                	totalValue=totalValue*10;
                	totalExp++;
                }
                 
                FASTRingBufferWriter.writeDecimal(ringBuffer, exponent, totalValue*accumSign);  
                break;
            
            default:
                throw new UnsupportedOperationException("Field was "+fieldType);
        }       
        
        //closing field so keep this new active position as the potential start for the next field
        bytePosStartField = bytePosActive;
        // ** write as we go close out the field

        aftetDot = false;
        beforeDotValue = 0;
        accumSign = 1;
        accumValue = 0;
        
        startingMessage = false;
        
    }

    @Override
    public void closeFrame() {        
    }

    //TODO: hack test for now this should be a ring buffer of ring buffers.
    List<FASTRingBuffer> ringBufferList = new ArrayList<FASTRingBuffer>();

    @Override
    public void openFrame() {
        //get new catalog if is has been changed by the other visitor
        byte[] catBytes = messageTypes.getCatBytes();
        if (!Arrays.equals(this.catBytes, catBytes)) {
            this.catBytes = catBytes;        
            catalog = new TemplateCatalogConfig(catBytes);
        //    System.err.println("new catalog");            
            
            //TODO: A, need to build FieldRectordExtractor so it matches the existing catalog, then we can start with a given catalog and remove this dynamic part.
            
            //TODO: check assumption that templateID 0 is the one for sending catalogs.
            
            //if any partial write of field data is in progress just throw it away because 
            //next frame will begin again from the start of the message.
            
         //   System.err.println("A "+ringBuffer.contentRemaining(ringBuffer));
            
                        
            //ignore any byte kept so far in this message
            bytePosStartField = bytePosActive = ringBuffer.addBytePos.value;
            FASTRingBuffer.abandonWrites(ringBuffer.headPos,ringBuffer.workingHeadPos);
            
         //   System.err.println("B "+ringBuffer.contentRemaining(ringBuffer));
          
         //   System.err.println("wrote bytes to position:"+ringBuffer.addBytePos.value);
            
            // Write new catalog to old stream stream so it is the last one written.
            FASTRingBufferWriter.writeInt(ringBuffer, CATALOG_TEMPLATE_ID);        
            FASTRingBufferWriter.writeBytes(ringBuffer, catBytes);               
            
			//System.err.println("length "+catBytes.length);
			//System.err.println(Arrays.toString(catBytes));
            
            FASTRingBuffer.publishWrites(ringBuffer.headPos, ringBuffer.workingHeadPos);
         //   System.err.println("C "+ringBuffer.contentRemaining(ringBuffer)+"  "+catBytes.length);
            
            //now create new ring buffer and chain them
            FASTRingBuffer newRingBuffer = catalog.ringBuffers().buffers[0];
            
            ringBufferList.add(newRingBuffer);
            
            //TODO: chain these ring buffers
             ringBuffer = newRingBuffer;
            
        }        
    }


	public FASTRingBuffer getRingBuffer(int index) {
		//TODO: clear all previous values.
		//TODO: must block until first rb is found?
		while (ringBufferList.size()<=0) {
			Thread.yield();//TODO: poor choice but still thinking about this.
		}
		return ringBufferList.get(index);
	//	return ringBuffer;
	}

}
