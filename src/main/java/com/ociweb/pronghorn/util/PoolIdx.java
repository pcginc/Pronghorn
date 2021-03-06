package com.ociweb.pronghorn.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PoolIdx  {

    private final long[] keys;
    private final byte[] locked;
    private final int groups;
    private final int step;
    private long locksTaken = 0;
    private long locksReleased = 0;
    private Runnable firstUsage;
    private Runnable noLocks;
    private final static Logger logger = LoggerFactory.getLogger(PoolIdx.class);
    
    public PoolIdx(int length, int groups) {
        this.keys = new long[length];
        this.locked = new byte[length];
        this.groups = groups;
        
        if ((length%groups != 0) || (groups>length)) {
        	throw new UnsupportedOperationException("length must be divisiable by groups value.");
        }
        
        this.step = length/groups;
        
    }
    
    public int length() {
    	return keys.length;
    }
    
    public String toString() { 
    	StringBuilder builder = new StringBuilder();
    	
    	for(int i = 0;i<keys.length;i++) {
    		
    		builder.append(" I:");
    		Appendables.appendValue(builder, i);
    		
    		builder.append(" K:");
    		Appendables.appendValue(builder, keys[i]);
    		
    		builder.append(" L:");
    		Appendables.appendValue(builder, locked[i]);
    		builder.append("\n");
    	}
    	return builder.toString();
    }    
    
    public int getIfReserved(long key) {   
    	return getIfReserved(this,key);
    }
    
    public static int getIfReserved(PoolIdx that, long key) {   
    	
    	long[] localKeys = that.keys;
    	byte[] localLocked = that.locked;
        int i = localKeys.length;
        int idx = -1;
        //linear search for this key. TODO: if member array is 100 or bigger we should consider hashTable
        while (--i>=0) {
            //found and returned member that matches key and was locked
            if (0 == localLocked[i] || key != localKeys[i] ) {
            	//this slot was not locked so remember it
            	//we may want to use this slot if key is not found.
            	if (idx < 0 && 0 == localLocked[i]) {
            		idx = i;
            	}
            } else {
            	return i;//this is the rare case
            }
        }
        return -1;
    }
    
    public void setFirstUsageCallback(Runnable run) {
    	firstUsage = run;
    }
    
    public void setNoLocksCallback(Runnable run) {
    	noLocks = run;
    }
    
    public int get(long key) {
    	return get(this,key);
    }
    
    public static int get(PoolIdx that, long key) {   
    	
        long[] localKeys = that.keys;
        byte[] localLocked = that.locked;
        
        int idx = -1;
       
        int g = that.groups;
        while (--g>=0) {
        	int j = that.step;        
	        while (--j>=0) {
	        	///////////
	        	int temp = (g*that.step)+j;
	            /////////
	            //found and returned member that matches key and was locked
	            if (key == localKeys[temp] && 1 == localLocked[temp]) {
	                return temp;
	            } else {
	                //this slot was not locked so remember it
	                //we may want to use this slot if key is not found.
	                if (idx < 0 && 0 == localLocked[temp]) {
	                    idx = temp;
	                }
	            }
	        }
        }
        
        
        return startNewLock(that, key, idx);
    }
    

    
    /**
     * 
     * @param key
     * @param isOk filter to ensure that only acceptable values are chosen
     */
    public int get(long key, PoolIdxPredicate isOk) {   
    	
        int idx = -1;
        
        int g = groups;
        while (--g>=0) {
        	int j = step;        
	        while (--j>=0) {
	        	///////////
	        	int temp = (g*step)+j;
	            /////////
	        	
	        	//found and returned member that matches key and was locked
	            if (key == keys[temp] && 1 == locked[temp]) {
	            	
	                return temp;
	            } else {
	                //this slot was not locked so remember it
	                //we may want to use this slot if key is not found.
	                if (idx < 0 && 0 == locked[temp] && isOk.isOk(temp)) {
	                    idx = temp;
	                }
	            }
	        }  
        }

        return startNewLock(this, key, idx);
    }

    private int failureCount = 0;
    
    private static int startNewLock(PoolIdx that, long key, int idx) {
        if (idx>=0) {
        	if (0==that.locksTaken && that.firstUsage!=null) {
        		that.firstUsage.run();
        	}
        	that.locksTaken++;
        	that.locked[idx] = 1;
        	that.keys[idx] = key;
            return idx;
        } else {
        	
        	boolean debug = false;
        	if (debug) {
	        	//DO NOT REPORT UNLESS DEBUGGING.
	        	//unable to find a lock, report this.
	        	if (Integer.numberOfLeadingZeros(that.failureCount) != Integer.numberOfLeadingZeros(++that.failureCount)) {
	        		logger.info("Unable to find free value from the pool, consider modification of the graph/configuration.");
	        	}     	
        	}
        	
            return -1;
        }
    }
    
    /**
     * 
     * @param key
     * @return the released pool index value
     */
    public int release(long key) {
        int i = keys.length;
        while (--i>=0) {
            if (key==keys[i]) {
            	locksReleased++;
            	//System.err.println("locks tken "+locksTaken+" and released "+locksReleased);
            	if ((locksReleased==locksTaken) && (noLocks!=null)) {
            		noLocks.run();
            	}
                locked[i] = 0;
                return i;
            }
        } 
        return -1;
    }
    
    public int locks() {
        return (int)(locksTaken-locksReleased);
    }
    
    
    
}
