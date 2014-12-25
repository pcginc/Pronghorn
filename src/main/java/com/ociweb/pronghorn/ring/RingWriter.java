package com.ociweb.pronghorn.ring;

import java.nio.ByteBuffer;



public class RingWriter {

  static double[] powd = new double[] {
	  1.0E-64,1.0E-63,1.0E-62,1.0E-61,1.0E-60,1.0E-59,1.0E-58,1.0E-57,1.0E-56,1.0E-55,1.0E-54,1.0E-53,1.0E-52,1.0E-51,1.0E-50,1.0E-49,1.0E-48,1.0E-47,1.0E-46,
	  1.0E-45,1.0E-44,1.0E-43,1.0E-42,1.0E-41,1.0E-40,1.0E-39,1.0E-38,1.0E-37,1.0E-36,1.0E-35,1.0E-34,1.0E-33,1.0E-32,1.0E-31,1.0E-30,1.0E-29,1.0E-28,1.0E-27,1.0E-26,1.0E-25,1.0E-24,1.0E-23,1.0E-22,
	  1.0E-21,1.0E-20,1.0E-19,1.0E-18,1.0E-17,1.0E-16,1.0E-15,1.0E-14,1.0E-13,1.0E-12,1.0E-11,1.0E-10,1.0E-9,1.0E-8,1.0E-7,1.0E-6,1.0E-5,1.0E-4,0.001,0.01,0.1,1.0,10.0,100.0,1000.0,10000.0,100000.0,1000000.0,
	  1.0E7,1.0E8,1.0E9,1.0E10,1.0E11,1.0E12,1.0E13,1.0E14,1.0E15,1.0E16,1.0E17,1.0E18,1.0E19,1.0E20,1.0E21,1.0E22,1.0E23,1.0E24,1.0E25,1.0E26,1.0E27,1.0E28,1.0E29,1.0E30,1.0E31,1.0E32,1.0E33,1.0E34,1.0E35,
	  1.0E36,1.0E37,1.0E38,1.0E39,1.0E40,1.0E41,1.0E42,1.0E43,1.0E44,1.0E45,1.0E46,1.0E47,1.0E48,1.0E49,1.0E50,1.0E51,1.0E52,1.0E53,1.0E54,1.0E55,1.0E56,1.0E57,1.0E58,1.0E59,1.0E60,1.0E61,1.0E62,1.0E63,1.0E64};

  static float[] powf = new float[] {
	  Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,
	  1.0E-45f,1.0E-44f,1.0E-43f,1.0E-42f,1.0E-41f,1.0E-40f,1.0E-39f,1.0E-38f,1.0E-37f,1.0E-36f,1.0E-35f,1.0E-34f,1.0E-33f,1.0E-32f,1.0E-31f,1.0E-30f,1.0E-29f,1.0E-28f,1.0E-27f,1.0E-26f,1.0E-25f,1.0E-24f,1.0E-23f,1.0E-22f,
	  1.0E-21f,1.0E-20f,1.0E-19f,1.0E-18f,1.0E-17f,1.0E-16f,1.0E-15f,1.0E-14f,1.0E-13f,1.0E-12f,1.0E-11f,1.0E-10f,1.0E-9f,1.0E-8f,1.0E-7f,1.0E-6f,1.0E-5f,1.0E-4f,0.001f,0.01f,0.1f,1.0f,10.0f,100.0f,1000.0f,10000.0f,100000.0f,1000000.0f,
	  1.0E7f,1.0E8f,1.0E9f,1.0E10f,1.0E11f,1.0E12f,1.0E13f,1.0E14f,1.0E15f,1.0E16f,1.0E17f,1.0E18f,1.0E19f,1.0E20f,1.0E21f,1.0E22f,1.0E23f,1.0E24f,1.0E25f,1.0E26f,1.0E27f,1.0E28f,1.0E29f,1.0E30f,1.0E31f,1.0E32f,1.0E33f,1.0E34f,1.0E35f,
	  1.0E36f,1.0E37f,1.0E38f,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN,Float.NaN};

	    
    public static void writeInt(RingBuffer rb, int value) {
        RingBuffer.addValue(rb.buffer, rb.mask, rb.workingHeadPos, value);        
    }
    
    public static void writeLong(RingBuffer rb, long value) {
        RingBuffer.addValue(rb.buffer, rb.mask, rb.workingHeadPos, (int)(value >>> 32), (int)value & 0xFFFFFFFF );    
    }

    public static void writeDecimal(RingBuffer rb, int exponent, long mantissa) {
        RingBuffer.addValue(rb.buffer, rb.mask, rb.workingHeadPos, exponent);   
        RingBuffer.addValue(rb.buffer, rb.mask, rb.workingHeadPos, (int) (mantissa >>> 32), (int)mantissa & 0xFFFFFFFF );    
    }

    public static void writeFloatToIntBits(RingBuffer rb, float value) {
    	writeInt(rb, Float.floatToIntBits(value));
    }
    
    public static void writeDoubleToLongBits(RingBuffer rb, double value) {
    	writeLong(rb, Double.doubleToLongBits(value));
    }    
    
//    public static float readFloat(RingBuffer ring, int idx) {
//        return ((float)readDecimalMantissa(ring,(OFF_MASK&idx)))
//        		         *powfi[64*readDecimalExponent(ring,(OFF_MASK&idx))];
//    }
    
    
    //Because the stream needs to be safe and write the bytes ahead to the buffer we need 
    //to set the new byte pos, pos/len ints as a separate call
    public static void finishWriteBytesAlreadyStarted(RingBuffer rb, int p, int length) {
    	rb.validateVarLength(length);
    	
        RingBuffer.addValue(rb.buffer, rb.mask, rb.workingHeadPos, p);
 //       System.err.println("writeLen:"+length+" at "+rb.workingHeadPos.value+" mod "+(rb.mask&rb.workingHeadPos.value));
        RingBuffer.addValue(rb.buffer, rb.mask, rb.workingHeadPos, length);

        rb.byteWorkingHeadPos.value = p + length;
        
    }

    public static void writeBytes(RingBuffer rb, byte[] source) {
    	rb.validateVarLength(source.length);
        RingBuffer.addByteArray(source, 0, source.length, rb);
    }
    
	
    public static void writeBytes(RingBuffer rb, ByteBuffer source, int position, int length) {
    	rb.validateVarLength(length);
    	if ((position&rb.byteMask) > ((position+length-1)&rb.byteMask)) {
    		int temp = 1 + rb.mask - (position & rb.mask);
    		source.get(rb.byteBuffer, position & rb.byteMask, temp);
    		source.get(rb.byteBuffer, 0, length - temp);					    		
    	} else {					    	
    		source.get(rb.byteBuffer, position&rb.byteMask, length);
    	}
    	finishWriteBytesAlreadyStarted(rb, position, length);
    }
    
    
    public static void writeASCII(RingBuffer rb, char[] source) {
    	rb.validateVarLength(source.length);
        RingWriter.addASCIIToRing(source, 0, source.length, rb);
    }
    
    public static void writeASCII(RingBuffer rb, char[] source, int offset, int length) {
    	rb.validateVarLength(length);
        RingWriter.addASCIIToRing(source, offset, length, rb);
    }
    
    public static void writeASCII(RingBuffer rb, CharSequence source) {
    	rb.validateVarLength(source.length());
        RingWriter.addASCIIToRing(source, rb);
    }
    
    public static void writeUTF8(RingBuffer rb, char[] source) {
    	rb.validateVarLength(source.length<<3); //UTF8 encoded bytes are longer than the char count (6 is the max but math for 8 is cheaper)
        RingWriter.addUTF8ToRing(source, 0, source.length, rb);
    }
    
    public static void writeUTF8(RingBuffer rb, char[] source, int offset, int length) {
    	rb.validateVarLength(length<<3);//UTF8 encoded bytes are longer than the char count (6 is the max but math for 8 is cheaper)
        RingWriter.addUTF8ToRing(source, offset, length, rb);
    }
    
    public static void writeUTF8(RingBuffer rb, CharSequence source) {
    	rb.validateVarLength(source.length()<<3);//UTF8 encoded bytes are longer than the char count (6 is the max but math for 8 is cheaper)
        RingWriter.addUTF8ToRing(source, rb);
    }

	private static void addASCIIToRing(char[] source, int sourceIdx, int sourceLen, RingBuffer rbRingBuffer) {
		
	    final int p = rbRingBuffer.byteWorkingHeadPos.value;
	    if (sourceLen > 0) {
	    	int targetMask = rbRingBuffer.byteMask;
	    	int proposedEnd = p + sourceLen;
			byte[] target = rbRingBuffer.byteBuffer;        	
			
	        int tStop = (p + sourceLen) & targetMask;
			int tStart = p & targetMask;
			if (tStop > tStart) {
				RingWriter.copyASCIIToByte(source, sourceIdx, target, tStart, sourceLen);
			} else {
			    // done as two copies
			    int firstLen = 1+ targetMask - tStart;
			    RingWriter.copyASCIIToByte(source, sourceIdx, target, tStart, firstLen);
			    RingWriter.copyASCIIToByte(source, sourceIdx + firstLen, target, 0, sourceLen - firstLen);
			}
	        rbRingBuffer.byteWorkingHeadPos.value = proposedEnd;
	    }        
	    
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, p);
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, sourceLen);
	}

	private static void copyASCIIToByte(char[] source, int sourceIdx, byte[] target, int targetIdx, int len) {
		int i = len;
		while (--i>=0) {
			target[targetIdx+i] = (byte)(0xFF&source[sourceIdx+i]);
		}
	}
	
	private static void addASCIIToRing(CharSequence source, RingBuffer rbRingBuffer) {
		
	    final int p = rbRingBuffer.byteWorkingHeadPos.value;
	    int sourceLen = source.length();
	    if (sourceLen > 0) {
	    	int targetMask = rbRingBuffer.byteMask;
	    	int proposedEnd = p + sourceLen;
			byte[] target = rbRingBuffer.byteBuffer;        	
			
	        int tStart = p & targetMask;
			if (tStart < ((p + sourceLen - 1) & targetMask)) {
				RingWriter.copyASCIIToByte(source, 0, target, tStart, sourceLen);
			} else {
			    // done as two copies
			    int firstLen = 1+ targetMask - tStart;
			    RingWriter.copyASCIIToByte(source, 0, target, tStart, firstLen);
			    RingWriter.copyASCIIToByte(source, firstLen, target, 0, sourceLen - firstLen);
			}
	        rbRingBuffer.byteWorkingHeadPos.value = proposedEnd;
	    }        
	    
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, p);
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, sourceLen);
	}

	
	private static void addUTF8ToRing(CharSequence source, RingBuffer rbRingBuffer) {
		
	    final int p = rbRingBuffer.byteWorkingHeadPos.value;
	    int sourceLen = source.length();
	    if (sourceLen > 0) {
	    	int targetMask = rbRingBuffer.byteMask;
	    	int proposedEnd = p + sourceLen;
			byte[] target = rbRingBuffer.byteBuffer;        	
			
	        int tStop = (p + sourceLen) & targetMask;
			int tStart = p & targetMask;
			if (tStop > tStart) {
				RingWriter.copyUTF8ToByte(source, 0, target, tStart, sourceLen);
			} else {
			    // done as two copies
			    int firstLen = 1+ targetMask - tStart;
			    RingWriter.copyUTF8ToByte(source, 0, target, tStart, firstLen);
			    RingWriter.copyUTF8ToByte(source, firstLen, target, 0, sourceLen - firstLen);
			}
	        rbRingBuffer.byteWorkingHeadPos.value = proposedEnd;
	    }        
	    
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, p);
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, sourceLen);
	}
	
	private static void copyASCIIToByte(CharSequence source, int sourceIdx, byte[] target, int targetIdx, int len) {
		int i = len; //System.err.println("write len:"+len);
		while (--i>=0) {
			target[targetIdx+i] = (byte)(0xFF&source.charAt(sourceIdx+i));
		}
	}
	
	private static void copyUTF8ToByte(CharSequence source, int sourceIdx, byte[] target, int targetIdx, int len) {

        int pos = targetIdx;
        int c = 0;        
	    while (c < len) {
	        pos = RingWriter.encodeSingleChar((int) source.charAt(sourceIdx+c++), target, pos);
	    }		

	}
	
	private static void addUTF8ToRing(char[] source, int sourceIdx, int sourceLen, RingBuffer rbRingBuffer) {
		
	    final int p = rbRingBuffer.byteWorkingHeadPos.value;
	    if (sourceLen > 0) {
	    	int targetMask = rbRingBuffer.byteMask;
	    	int proposedEnd = p + sourceLen;
			byte[] target = rbRingBuffer.byteBuffer;        	
			
	        int tStop = (p + sourceLen) & targetMask;
			int tStart = p & targetMask;
			if (tStop > tStart) {
				RingWriter.copyUTF8ToByte(source, sourceIdx, target, tStart, sourceLen);
			} else {
			    // done as two copies
			    int firstLen = 1+ targetMask - tStart;
			    RingWriter.copyUTF8ToByte(source, sourceIdx, target, tStart, firstLen);
			    RingWriter.copyUTF8ToByte(source, sourceIdx + firstLen, target, 0, sourceLen - firstLen);
			}
	        rbRingBuffer.byteWorkingHeadPos.value = proposedEnd;
	    }        
	    
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, p);
	    RingBuffer.addValue(rbRingBuffer.buffer, rbRingBuffer.mask, rbRingBuffer.workingHeadPos, sourceLen);
	}
	
	private static void copyUTF8ToByte(char[] source, int sourceIdx, byte[] target, int targetIdx, int len) {

        int pos = targetIdx;
        int c = 0;        
	    while (c < len) {
	        pos = RingWriter.encodeSingleChar((int) source[sourceIdx+c++], target, pos);
	    }		

	}

	public static int encodeSingleChar(int c, byte[] buffer, int pos) {
	    if (c <= 0x007F) {
	        // code point 7
	        buffer[pos++] = (byte) c;
	    } else {
	        if (c <= 0x07FF) {
	            // code point 11
	            buffer[pos++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
	        } else {
	            if (c <= 0xFFFF) {
	                // code point 16
	                buffer[pos++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
	            } else {
	                if (c < 0x1FFFFF) {
	                    // code point 21
	                    buffer[pos++] = (byte) (0xF0 | ((c >> 18) & 0x07));
	                } else {
	                    if (c < 0x3FFFFFF) {
	                        // code point 26
	                        buffer[pos++] = (byte) (0xF8 | ((c >> 24) & 0x03));
	                    } else {
	                        if (c < 0x7FFFFFFF) {
	                            // code point 31
	                            buffer[pos++] = (byte) (0xFC | ((c >> 30) & 0x01));
	                        } else {
	                            throw new UnsupportedOperationException("can not encode char with value: " + c);
	                        }
	                        buffer[pos++] = (byte) (0x80 | ((c >> 24) & 0x3F));
	                    }
	                    buffer[pos++] = (byte) (0x80 | ((c >> 18) & 0x3F));
	                }
	                buffer[pos++] = (byte) (0x80 | ((c >> 12) & 0x3F));
	            }
	            buffer[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
	        }
	        buffer[pos++] = (byte) (0x80 | ((c) & 0x3F));
	    }
	    return pos;
	}
    
    
}
