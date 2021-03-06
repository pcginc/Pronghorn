package com.ociweb.pronghorn.network;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.network.schema.NetPayloadSchema;
import com.ociweb.pronghorn.network.schema.ReleaseSchema;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;
import com.ociweb.pronghorn.util.Appendables;
import com.ociweb.pronghorn.util.ServiceObjectHolder;

/**
 * Server-side stage that writes back to the socket. Useful for building a server.
 *
 * @author Nathan Tippy
 * @see <a href="https://github.com/objectcomputing/Pronghorn">Pronghorn</a>
 */
public class ServerSocketWriterStage extends PronghornStage {
    
    private static Logger logger = LoggerFactory.getLogger(ServerSocketWriterStage.class);
    public static boolean showWrites = false;
    
	//TODO: by adding accessor method and clearing the bufferChecked can make this grow at runtime if needed.
	public static int MINIMUM_BUFFER_SIZE = 1<<21; //2mb default minimum
	
    private final Pipe<NetPayloadSchema>[] input;
    private final Pipe<ReleaseSchema> releasePipe;
    
    private final ServerCoordinator coordinator;
    
    private ByteBuffer    workingBuffers[];
    private boolean       bufferChecked[];
    private SocketChannel writeToChannel[];
    private long          writeToChannelId[];
    private int           writeToChannelMsg[];
    private int           writeToChannelBatchCountDown[]; 
    
    private long activeTails[];
    private long activeIds[]; 
    private int activeMessageIds[];    
  
    private long totalBytesWritten = 0;

    private int maxBatchCount;

	private static final boolean enableWriteBatching = true;  
    

	private final boolean debugWithSlowWrites = false; //TODO: set from coordinator, NOTE: this is a critical piece of the tests
	private final int debugMaxBlockSize = 7;//50000;
	
	private GraphManager graphManager;
	
    
    /**
     * 
     * Writes pay-load back to the appropriate channel based on the channelId in the message.
     * 
     * + ServerResponseSchema is custom to this stage and supports all the features here
     * + Has support for upgrade redirect pipe change (Module can clear bit to prevent this if needed)
     * + Has support for closing connection after write as needed for HTTP 1.1 and 0.0
     * 
     * 
     * + Will Have support for writing same pay-load to multiple channels (subscriptions)
     * + Will Have support for order enforcement and pipelined requests
     * 
     * 
     * @param graphManager
     * @param coordinator
     * @param dataToSend _in_ The data to be written to the socket.
     */
    public ServerSocketWriterStage(GraphManager graphManager, ServerCoordinator coordinator, Pipe<NetPayloadSchema>[] dataToSend) {
        super(graphManager, dataToSend, NONE);
        this.coordinator = coordinator;
        this.input = dataToSend;
        this.releasePipe = null;
     
        this.graphManager = graphManager;

        GraphManager.addNota(graphManager, GraphManager.DOT_BACKGROUND, "lemonchiffon3", this);
        GraphManager.addNota(graphManager, GraphManager.LOAD_MERGE, GraphManager.LOAD_MERGE, this);
    }
    
    
    //optional ack mode for testing and other configuraitons..  
    
    public ServerSocketWriterStage(GraphManager graphManager, ServerCoordinator coordinator, Pipe<NetPayloadSchema>[] input, Pipe<ReleaseSchema> releasePipe) {
        super(graphManager, input, releasePipe);
        this.coordinator = coordinator;
        this.input = input;
        this.releasePipe = releasePipe;
          
        
        this.graphManager = graphManager;
       
        GraphManager.addNota(graphManager, GraphManager.DOT_BACKGROUND, "lemonchiffon3", this);
        GraphManager.addNota(graphManager, GraphManager.LOAD_MERGE, GraphManager.LOAD_MERGE, this);
    }
    
    @Override
    public void startup() {
    	
    	final Number rate = (Number)GraphManager.getNota(graphManager, this, GraphManager.SCHEDULE_RATE, null);
    	    	
    	//this is 20ms for the default max limit
    	long hardLimtNS = 20_000_000; //May revisit later.
        //also note however data can be written earlier if:
    	//   1. the buffer has run out of space (the multiplier controls this)
    	//   2. if the pipe has no more data.
    	
    	this.maxBatchCount = null==rate ? 16 : (int)(hardLimtNS/rate.longValue());  	
    	
    	int c = input.length;

    	writeToChannel = new SocketChannel[c];
    	writeToChannelId = new long[c];
    	writeToChannelMsg = new int[c];
    	writeToChannelBatchCountDown = new int[c];
    	
    	workingBuffers = new ByteBuffer[c];
    	bufferChecked = new boolean[c];
    	activeTails = new long[c];
    	activeIds = new long[c];
    	activeMessageIds = new int[c];
    	Arrays.fill(activeTails, -1);   	
    	
		int j = c;
		while (--j>=0) {
			//warning: this is the entire ring and may be too large.
			workingBuffers[j] = ByteBuffer.allocateDirect(
						Math.max(MINIMUM_BUFFER_SIZE, 
							input[j].sizeOfBlobRing)					
					);
		}
    	
    }
    
    @Override
    public void shutdown() {

    }
    
    @Override
    public void run() {
       
    	boolean didWork = false;
    	boolean doingWork = false;
    	do {
    		doingWork = false;
	    	int x = input.length;
	    	while (--x>=0) {
	    		
	    		//ensure all writes are complete
	    		if (null == writeToChannel[x]) {	
	    			//second check for full content is critical or the data gets copied too soon
	    			if (Pipe.isEmpty(input[x]) || !Pipe.hasContentToRead(input[x])) {    				
	    				//no content to read on the pipe
	    				//all the old data has been written so the writeChannel remains null	    		
	    			} else {
	    	
		            	int activeMessageId = Pipe.takeMsgIdx(input[x]);		            			            	
		            	processMessage(activeMessageId, x);
		            	doingWork |= (activeMessageId < 0);
	    			}
	    			
	    		} else {
	    			Pipe<NetPayloadSchema> localInput = input[x];
	 
	    			ByteBuffer localWorkingBuffer = workingBuffers[x];
	    			
	    			boolean hasRoomToWrite = localWorkingBuffer.capacity()-localWorkingBuffer.limit() > localInput.maxVarLen;
	    			//note writeToChannelBatchCountDown is set to zero when nothing else can be combined...
	    			if (--writeToChannelBatchCountDown[x]<=0 
	    				|| !hasRoomToWrite
	    				|| !Pipe.hasContentToRead(localInput) //myPipeHasNoData so fire now
	    				) {
	    				writeToChannelMsg[x] = -1;
		    			if (!(doingWork = writeDataToChannel(x))) {
		    				break;//network blocked so try again later 
		    			}	
	    			} else {
	    				
	    				//unflip
	    				int p = ((Buffer)localWorkingBuffer).limit();
	    				((Buffer)localWorkingBuffer).limit(localWorkingBuffer.capacity());
	    				((Buffer)localWorkingBuffer).position(p);	    				
	    				
	    				int h = 0;
	    				while (	isNextMessageMergeable(localInput, writeToChannelMsg[x], x, writeToChannelId[x], false) ) {
	    					h++;
	    					//logger.info("opportunity found to batch writes going to {} ", writeToChannelId[x]);
	    						    					
	    					mergeNextMessage(writeToChannelMsg[x], x, localInput, writeToChannelId[x]);
	    						    					
	    				}	
	    				if (Pipe.hasContentToRead(localInput)) {
	    					writeToChannelBatchCountDown[x] = 0;//send now nothing else is mergable
	    				}
	    				
	    				if (h>0) {
	    					Pipe.releaseAllPendingReadLock(localInput);
	    				}
	    				((Buffer)localWorkingBuffer).flip();

	    			}
	    			
	    		}    		
	    		
	    	}
	    	didWork |= doingWork;
    	} while (doingWork);
    	
		//we have no pipes to monitor so this must be done explicitly
	    if (didWork && (null != this.didWorkMonitor)) {
	    	this.didWorkMonitor.published();
	    }

    }
    
    long lastTotalBytes = 0;

	private void processMessage(int activeMessageId, int idx) {
		
		activeMessageIds[idx] = activeMessageId;
		
		//logger.info("sever to write {}",activeMessageId);
		
						
		if ( (NetPayloadSchema.MSG_PLAIN_210 == activeMessageId) ||
		     (NetPayloadSchema.MSG_ENCRYPTED_200 == activeMessageId) ) {
			            		
			loadPayloadForXmit(activeMessageId, idx);

		} else if (NetPayloadSchema.MSG_DISCONNECT_203 == activeMessageId) {

			final long channelId = Pipe.takeLong(input[idx]);
		    Pipe.confirmLowLevelRead(input[idx], Pipe.sizeOf(input[idx], activeMessageId));
		    Pipe.releaseReadLock(input[idx]);
		    assert(Pipe.contentRemaining(input[idx])>=0);
		 
		    coordinator.releaseResponsePipeLineIdx(channelId);//upon disconnect let go of pipe reservation
		    ServiceObjectHolder<ServerConnection> socketHolder = ServerCoordinator.getSocketChannelHolder(coordinator);
    
		    if (null!=socketHolder) {
		    	//logger.info("removed server id {}",channelId);
                //new Exception("removed server id "+channelId).printStackTrace();
                
		    	//we are disconnecting so we will remove the connection from the holder.
		        ServerConnection serverConnection = socketHolder.remove(channelId);	          
		        assert(null != serverConnection);
		        if (null != serverConnection) {
		        	//do not close since it is still known to sequence.
		        	serverConnection.decompose();
		        }
		    }	     
	   
		    
		    
		} else if (NetPayloadSchema.MSG_UPGRADE_307 == activeMessageId) {
			
			//set the pipe for any further communications
		    long channelId = Pipe.takeLong(input[idx]);
			int pipeIdx = Pipe.takeInt(input[idx]);
						
			ServerCoordinator.setUpgradePipe(coordinator, 
		    		channelId, //connection Id 
		    		pipeIdx); //pipe idx
		    
		    //switch to new reserved connection?? after upgrade no need to use http router
		    //perhaps? 	coordinator.releaseResponsePipeLineIdx(channelId);
		    //	 connection   setPoolReservation
		    //or...
			
		    
		    Pipe.confirmLowLevelRead(input[idx], Pipe.sizeOf(NetPayloadSchema.instance, NetPayloadSchema.MSG_UPGRADE_307));
		    Pipe.releaseReadLock(input[idx]);
		    assert(Pipe.contentRemaining(input[idx])>=0);
		    
		} else if (NetPayloadSchema.MSG_BEGIN_208 == activeMessageId) {
			int seqNo = Pipe.takeInt(input[idx]);
			Pipe.confirmLowLevelRead(input[idx], Pipe.sizeOf(NetPayloadSchema.instance, NetPayloadSchema.MSG_BEGIN_208));
			Pipe.releaseReadLock(input[idx]);
			
		} else if (activeMessageId < 0) {
		    
			Pipe.confirmLowLevelRead(input[idx], Pipe.EOF_SIZE);
		    Pipe.releaseReadLock(input[idx]);
		    assert(Pipe.contentRemaining(input[idx])>=0);
		    
		    //comes from muliple pipes so this can not be done yet.
		    //requestShutdown();	                    
		}
	}

	
    private void loadPayloadForXmit(final int msgIdx, final int idx) {
        
    	final int msgSize = Pipe.sizeOf(input[idx], msgIdx);
    	
        Pipe<NetPayloadSchema> pipe = input[idx];
        final long channelId = Pipe.takeLong(pipe);
        final long arrivalTime = Pipe.takeLong(pipe);        
               
        activeIds[idx] = channelId;
        if (NetPayloadSchema.MSG_PLAIN_210 == msgIdx) {
        	activeTails[idx] = Pipe.takeLong(pipe);
        } else {
        	assert(msgIdx == NetPayloadSchema.MSG_ENCRYPTED_200);
        	activeTails[idx] = -1;
        }
        //byteVector is payload
        int meta = Pipe.takeByteArrayMetaData(pipe); //for string and byte array
        int len = Pipe.takeByteArrayLength(pipe);
                
        assert(len>0) : "All socket writes must be of zero length or they should not be requested";
    
        ServiceObjectHolder<ServerConnection> socketHolder = ServerCoordinator.getSocketChannelHolder(coordinator);
        
        if (null!=socketHolder) {
	        ServerConnection serverConnection = socketHolder.get(channelId);
	        	        
	        //only write if this connection is still valid
	        if (null != serverConnection) {        
					    
	        	if (showWrites) {
	        		int pos = Pipe.convertToPosition(meta, pipe);
	        		logger.info("/////////len{}///////////\n"+
	        				Appendables.appendUTF8(new StringBuilder(), Pipe.blob(pipe), pos, len, Pipe.blobMask(pipe))
	        		+"\n////////////////////",len);
	        	}
	        	
	        	writeToChannel[idx] = serverConnection.getSocketChannel(); //ChannelId or SubscriptionId      
	        	writeToChannelId[idx] = channelId;
	        	writeToChannelMsg[idx] = msgIdx;
	        	writeToChannelBatchCountDown[idx] = maxBatchCount;

	        	
		        ByteBuffer[] writeBuffs = Pipe.wrappedReadingBuffers(pipe, meta, len);
		        
		        checkBuffers(idx, pipe, writeToChannel[idx]);
		        //lazy allocate since we need to wait for a socket to be created.
		        if (null == workingBuffers[idx]) {
					try {
						int sendBufSize = 
								Math.max(pipe.maxVarLen, 
								         writeToChannel[idx].getOption(StandardSocketOptions.SO_SNDBUF));
						
						sendBufSize *= 10;
						
						logger.info("new direct buffer of size {}",sendBufSize);
						workingBuffers[idx] = ByteBuffer.allocateDirect(sendBufSize);						
					} catch (IOException e) {
						new RuntimeException(e);
					}
		        }
		        
		        ((Buffer)workingBuffers[idx]).clear();
		        workingBuffers[idx].put(writeBuffs[0]);
		        workingBuffers[idx].put(writeBuffs[1]);
		        
		        assert(!writeBuffs[0].hasRemaining());
		        assert(!writeBuffs[1].hasRemaining());
		        		       		        
		        Pipe.confirmLowLevelRead(input[idx], msgSize);
		        
		        Pipe.readNextWithoutReleasingReadLock(input[idx]);
		        //Pipe.releaseReadLock(dataToSend[idx]);
		        
		        //In order to maximize throughput take all the messages which are gong to the same location.

		        //if there is content and this content is also a message to send and we still have room in the working buffer and the channel is the same then we can batch it.
		        while (enableWriteBatching && isNextMessageMergeable(pipe, msgIdx, idx, channelId, false) ) {		        			        	
		        	//logger.trace("opportunity found to batch writes going to {} ",channelId);
		        	
		        	mergeNextMessage(msgIdx, idx, pipe, channelId);
			        			      
		        }	
				if (Pipe.hasContentToRead(pipe)) {
					writeToChannelBatchCountDown[idx] = 0;//send now nothing else is mergable
				}
		        		        
		        Pipe.releaseAllPendingReadLock(input[idx]);
		
		        
		      //  logger.info("total bytes written {} ",totalBytesWritten);
		        
		       // logger.info("write bytes {} for id {}",workingBuffers[idx].position(),channelId);
		        
		        ((Buffer)workingBuffers[idx]).flip();
	        } else {
	        	//logger.info("\nno server connection found for id:{} droped bytes",channelId);
		        
		        Pipe.confirmLowLevelRead(pipe, msgSize);
		        Pipe.releaseReadLock(pipe);
	        }

        } else {
        	logger.error("Can not write, too early because SocketChannelHolder has not yet been created");
        }
                
    }

	private void mergeNextMessage(final int msgIdx, final int idx, Pipe<NetPayloadSchema> pipe, final long channelId) {
		
		final boolean takeTail = NetPayloadSchema.MSG_PLAIN_210 == msgIdx;
		
		int m = Pipe.takeMsgIdx(pipe);
		assert(m==msgIdx): "internal error";
		long c = Pipe.takeLong(pipe);
		
		long aTime = Pipe.takeLong(pipe);
		assert(c==channelId): "Internal error expected "+channelId+" but found "+c;
		
		
		if (takeTail) {
			activeTails[idx] =  Pipe.takeLong(pipe);
		} else {
			activeTails[idx] = -1;
		}
		int meta2 = Pipe.takeByteArrayMetaData(pipe); //for string and byte array
		int len2 = Pipe.takeByteArrayLength(pipe);
		ByteBuffer[] writeBuffs2 = Pipe.wrappedReadingBuffers(pipe, meta2, len2);
		
		workingBuffers[idx].put(writeBuffs2[0]);
		workingBuffers[idx].put(writeBuffs2[1]);
		
		assert(!writeBuffs2[0].hasRemaining());
		assert(!writeBuffs2[1].hasRemaining());
				        		
		Pipe.confirmLowLevelRead(pipe, Pipe.sizeOf(NetPayloadSchema.instance, msgIdx));
		Pipe.readNextWithoutReleasingReadLock(input[idx]);
	}

	private boolean isNextMessageMergeable(Pipe<NetPayloadSchema> pipe, final int msgIdx, final int idx, final long channelId, boolean debug) {

		if (debug) {
		    logger.info("Data {} {} {} {} ",
		    		    Pipe.hasContentToRead(pipe),
		    		    Pipe.peekInt(pipe)==msgIdx,
		    		    workingBuffers[idx].remaining()>pipe.maxVarLen,
		    		    Pipe.peekLong(pipe, 1)==channelId	    		
		    		);
		}
		
		return  Pipe.hasContentToRead(pipe) && 
				Pipe.peekInt(pipe)==msgIdx && 
				workingBuffers[idx].remaining()>pipe.maxVarLen && 
				Pipe.peekLong(pipe, 1)==channelId;
	}
    
	private void checkBuffers(int i, Pipe<NetPayloadSchema> pipe, SocketChannel socketChannel) {
		if (!bufferChecked[i]) {
			try {
				int minBufSize = 
						Math.max(pipe.maxVarLen, 
						         socketChannel.getOption(StandardSocketOptions.SO_SNDBUF));
				//logger.info("buffer is {} and must be larger than {}",workingBuffers[i].capacity(), minBufSize);
				if (workingBuffers[i].capacity()<minBufSize) {
					logger.info("new direct buffer of size {} created old one was too small.",minBufSize);
					workingBuffers[i] = ByteBuffer.allocateDirect(minBufSize);
				}
				bufferChecked[i] = true;
			} catch (ClosedChannelException cce) {
				bufferChecked[i] = true;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

    private boolean writeDataToChannel(int idx) {

    		boolean done = true;
    		if (!debugWithSlowWrites) {
		        try {
		        	
		        	ByteBuffer target = workingBuffers[idx];
		        	assert(target.isDirect());
		        	
		        	int bytesWritten = 0;
		        	do {		 
		        		bytesWritten = writeToChannel[idx].write(target);	
		        	
			        	if (bytesWritten>0) {
			        		totalBytesWritten+=bytesWritten;
			        	} else {
			        		break;
			        	}
			        	//output buffer may be too small so keep writing
		        	} while (target.hasRemaining());
	
		        	
		        	if (!target.hasRemaining()) {
		        		markDoneAndRelease(idx);
		        	} else {
		        		done = false;
		        	}
		        
		        } catch (IOException e) {
		        	//logger.trace("unable to write to channel",e);
		        	closeChannel(writeToChannel[idx]);
		            //unable to write to this socket, treat as closed
		            markDoneAndRelease(idx);
		        }
    		} else {
				
				ByteBuffer buf = ByteBuffer.wrap(new byte[debugMaxBlockSize]);
				buf.clear();
				
				int j = debugMaxBlockSize;
				int c = workingBuffers[idx].remaining();

				int p = workingBuffers[idx].position();
				while (--c>=0 && --j>=0) {
					buf.put(workingBuffers[idx].get(p++));
				}
				workingBuffers[idx].position(p);
								
				
				buf.flip();
				int expected = buf.limit();
								
				while (buf.hasRemaining()) {
					try {
						int len = writeToChannel[idx].write(buf);
						if (len>0) {
							expected -= len;
							totalBytesWritten += len;
						}
					} catch (IOException e) {
						//logger.error("unable to write to channel {} '{}'",e,e.getLocalizedMessage());
						closeChannel(writeToChannel[idx]);
			            //unable to write to this socket, treat as closed
			            markDoneAndRelease(idx);
			            
			            return false;
					}
				}

				if (expected!=0) {
					throw new UnsupportedOperationException();
				}
							
	        	if (!workingBuffers[idx].hasRemaining()) {
	        		markDoneAndRelease(idx);
	        	} else {
	        		done = false;
	        	}
    			
    		}
    		return done;
    }

    private void closeChannel(SocketChannel channel) {
        try {
        	if (channel.isOpen()) {
        		channel.close();
        	}
        } catch (IOException e1) {
            logger.warn("unable co close channel",e1);
        }
    }

    private void markDoneAndRelease(int idx) {
       
    	//System.err.println("done with connection");
    	((Buffer)workingBuffers[idx]).clear();
    	
    	writeToChannel[idx]=null;
        int sequenceNo = 0;//not available here
        if (null!=releasePipe) {
        	Pipe.presumeRoomForWrite(releasePipe);
        	publishRelease(releasePipe, activeIds[idx],
        			       activeTails[idx]!=-1?activeTails[idx]: Pipe.tailPosition(input[idx]),
        					sequenceNo);
        }
        //logger.info("write is complete for {} ", activeIds[idx]);
        
        //beginSocketStart
       // System.err.println();
        //long now = System.nanoTime();
        
//        Appendables.appendNearestTimeUnit(System.err, now-ServerCoordinator.acceptConnectionStart);
//        System.err.append(" round trip for call\n");
//        
//        Appendables.appendNearestTimeUnit(System.err, now-ServerCoordinator.acceptConnectionRespond);
//        System.err.append(" round trip for data gathering\n");
        
        
        
//        long duration3 = System.nanoTime()-ServerCoordinator.newDotRequestStart;
//        Appendables.appendNearestTimeUnit(System.err, duration3);
//        System.err.append(" new dot trip for call\n");
//
//        long duration2 = System.nanoTime()-ServerCoordinator.orderSuperStart;
//        Appendables.appendNearestTimeUnit(System.err, duration2);
//        System.err.append(" super order trip for call\n");
        
    }
   

	private static void publishRelease(Pipe<ReleaseSchema> pipe, long conId, long position, int sequenceNo) {
		assert(position!=-1);
		//logger.debug("sending release for {} at position {}",conId,position);
		
		int size = Pipe.addMsgIdx(pipe, ReleaseSchema.MSG_RELEASEWITHSEQ_101);
		Pipe.addLongValue(conId, pipe);
		Pipe.addLongValue(position, pipe);
		Pipe.addIntValue(sequenceNo, pipe);
		Pipe.confirmLowLevelWrite(pipe, size);
		Pipe.publishWrites(pipe);
				
	}
}
