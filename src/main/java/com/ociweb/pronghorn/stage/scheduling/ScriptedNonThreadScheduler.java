package com.ociweb.pronghorn.stage.scheduling;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.util.Appendables;
import com.ociweb.pronghorn.util.ma.RunningStdDev;
import com.ociweb.pronghorn.util.math.PMath;
import com.ociweb.pronghorn.util.math.ScriptedSchedule;

public class ScriptedNonThreadScheduler extends StageScheduler implements Runnable {

	//should have Numa truned on           -XX:+UseNUMA
	//should have priorities on for linux  -XX:+UseThreadPriorities -XX:+UseNUMA
	//thread pinning may be good as well.
	
    public static Appendable debugStageOrder = null; //turn on to investigate performance issues.
	
    private static final int NS_OPERATOR_FLOOR = 1000; //1 micro seconds
	private AtomicBoolean shutdownRequested = new AtomicBoolean(false);;
    private long[] rates;
    private long[] lastRun;
    public PronghornStage[] stages;
    private long[] sla;

    private DidWorkMonitor didWorkMonitor;
	private long deepSleepCycleLimt;
	
    private static AtomicInteger threadGroupIdGen = new AtomicInteger();
    
    private long maxRate;
    private static final Logger logger = LoggerFactory.getLogger(ScriptedNonThreadScheduler.class);

    private long nextRun = 0; //keeps times of the last pass so we need not check again

    private volatile Throwable firstException;//will remain null if nothing is wrong

    //Time based events will poll at least this many times over the period.
    // + ensures that the time trigger happens "near" the edge
    // + ensures that this non-thread scheduler in unit tests can capture the time delayed events.
    public static final int granularityMultiplier = 4;
    private static final long MS_TO_NS = 1_000_000;
    private static final long humanLimitNS = 10_000_000;
    

    private int[] producersIdx;

    private Pipe[] producerInputPipes;
    private long[] producerInputPipeHeads;

    private Pipe[] inputPipes;
    private long[] inputPipeHeads;
    public final boolean reverseOrder;

    private AtomicInteger isRunning = new AtomicInteger(0);
    private final GraphManager graphManager;
    private String name = "";
    private PronghornStage lastRunStage = null;
  
    private static final boolean debugNonReturningStages = false;

    private ScriptedSchedule schedule = null;


    
    public static boolean globalStartupLockCheck = false;
    static {
    	assert(globalStartupLockCheck=true);//yes this assigns and returns true   	
    }
    
    private boolean recordTime;    
    
    private long nextLongRunningCheck;
    private final long longRunningCheckFreqNS = 60_000_000_000L;//1 min
    private final StageVisitor checksForLongRuns;

	private boolean shownLowLatencyWarning = false;
    
	private ElapsedTimeRecorder sleepETL = null;//new ElapsedTimeRecorder();
	
    private byte[] stateArray;
    
    public int indexOfStage(PronghornStage stage) {
    	int i = stages.length;
    	while (--i>=0) {
    		if (stage == stages[i]) {
    			return i;
    		}
    	}
		return -1;
	}
    
    private void buildSchedule(GraphManager graphManager, 
    		                   PronghornStage[] stages, 
    		                   boolean reverseOrder) {

        this.stages = stages;
    	this.stateArray = GraphManager.stageStateArray(graphManager);    	
    	this.recordTime = GraphManager.isTelemetryEnabled(graphManager);
    	
    	final int groupId = threadGroupIdGen.incrementAndGet();
    	
    	this.didWorkMonitor = new DidWorkMonitor();
    	
    	
    	if (null==stages) {
    		schedule = new ScriptedSchedule(0, new int[0], 0);
    		//skipScript = new int[0];
    		return;
    	}
    	
    	this.sla = new long[stages.length];
    	int j = stages.length;
    	while (--j>=0) {
    		Number num = (Number) GraphManager.getNota(graphManager, stages[j].stageId, 
    				GraphManager.SLA_LATENCY, null);
    		this.sla[j] = null==num?Long.MAX_VALUE:num.longValue(); 
    	}
    	        
    	StringBuilder totalName = new StringBuilder();
    	
    	for(int s = 0; s<stages.length; s++) {
    		PronghornStage pronghornStage = stages[s];
    		Appendables.appendValue(totalName, pronghornStage.stageId).append(":");
    		totalName.append(pronghornStage.getClass().getSimpleName()
    				 .replace("Stage","")
    				 .replace("Supervisor","")
    				 .replace("Extraction","")
    				 .replace("Listener","L")
    				 .replace("Reader","R")
    				 .replace("Writer","W")    				 
    				 .replace("Server","S")
    				 .replace("Client","C"));
    		if (s<stages.length-1) {
    			totalName.append(",");
    		}
    	}
    	name = totalName.toString();

        // Pre-allocate rates based on number of stages.
    	final int defaultValue = 2_000_000;
        long rates[] = new long[stages.length];

        int k = stages.length;
        while (--k>=0) {
        	
        	//set thread name
        	if (groupId>=0) {
        		GraphManager.recordThreadGroup(stages[k], groupId, graphManager);
        	}
        	
        	//add monitoring to each pipe
 
    		PronghornStage.addWorkMonitor(stages[k], didWorkMonitor);
    		GraphManager.setPublishListener(graphManager, stages[k], didWorkMonitor);
    		GraphManager.setReleaseListener(graphManager, stages[k], didWorkMonitor);
       
        	
        	// Determine rates for each stage.
			long scheduleRate = Long.valueOf(String.valueOf(GraphManager.getNota(graphManager, stages[k], GraphManager.SCHEDULE_RATE, defaultValue)));
            rates[k] = scheduleRate;
        }

        // Build the script.
        schedule = PMath.buildScriptedSchedule(rates, reverseOrder);

        //in cycles but under the human perception of time
        deepSleepCycleLimt = humanLimitNS/schedule.commonClock;

        if (null != debugStageOrder) {	
        	try {
	        	debugStageOrder.append("----------full stages -------------Clock:");
	        	Appendables.appendValue(debugStageOrder, schedule.commonClock);
	        	if (null!=graphManager.name) {
	        		debugStageOrder.append(" ").append(graphManager.name);
	        	}
	        	
	        	debugStageOrder.append("\n");
	        	
		        for(int i = 0; i<stages.length; i++) {
		        	
		        	debugStageOrder.append("   ");
		        	debugStageOrder.append(i+" full stages "+stages[i].getClass().getSimpleName()+":"+stages[i].stageId);
		        	debugStageOrder.append("  inputs:");
		    		GraphManager.appendInputs(graphManager, debugStageOrder, stages[i]);
		    		debugStageOrder.append(" outputs:");
		    		GraphManager.appendOutputs(graphManager, debugStageOrder, stages[i]);
		    		debugStageOrder.append("\n");
		        	
		        }
		        
        	} catch (IOException e) {
        		throw new RuntimeException(e);
        	}
        }
        
        nextLongRunningCheck = System.nanoTime()+(longRunningCheckFreqNS/6);//first check is quicker.
        
        //System.err.println("commonClock:"+schedule.commonClock);
        
    }

    private long threadId;
	public void setThreadId(long id) {
		id = threadId;
	}
    
    public void detectHangingThread(long now, long timeoutNS) {

		if (didWorkMonitor.isOverTimeout(now, timeoutNS)) {
			didWorkMonitor.interrupt();
		}
	
    }

    public ScriptedNonThreadScheduler(GraphManager graphManager,
    								  StageVisitor checksForLongRuns,
    		                          boolean reverseOrder) {
        super(graphManager);
        this.graphManager = graphManager;
        this.checksForLongRuns = checksForLongRuns;
        this.reverseOrder = reverseOrder;
        
        PronghornStage[] temp = null;

	    PronghornStage[][] orderedStages = ScriptedFixedThreadsScheduler.buildStageGroups(graphManager, 1, true);
	   
	    int i = orderedStages.length;
	    while (--i>=0) {
	    	if (null != orderedStages[i]) {
	    		
	    		if (null == temp) {
	    			temp = orderedStages[i];
	    		} else {
	    			logger.trace("warning had to roll up, check the hard limit on threads");
	    			
	    			//roll up any stages
	    			PronghornStage[] additional = orderedStages[i];
	    			PronghornStage[] newList = new PronghornStage[temp.length+additional.length];
	    			
	    			System.arraycopy(temp, 0, newList, 0, temp.length);
	    			System.arraycopy(additional, 0, newList, temp.length, additional.length);
	    			
	    			temp = newList;
	    			
	    		}
	    	} 
	    }
	    
        buildSchedule(graphManager, temp, reverseOrder);
    }

    public ScriptedNonThreadScheduler(GraphManager graphManager, boolean reverseOrder, PronghornStage[] stages) {
    	this(graphManager, reverseOrder, null, stages);
    }
        
    
    public ScriptedNonThreadScheduler(GraphManager graphManager, boolean reverseOrder, StageVisitor checksForLongRuns, PronghornStage[] stages) {
        super(graphManager);
        this.graphManager = graphManager;
        this.checksForLongRuns = checksForLongRuns;
        this.reverseOrder = reverseOrder;

        buildSchedule(graphManager, stages, reverseOrder);
    }
    
    public ScriptedSchedule schedule() {
    	return schedule;
    }
    
    RunningStdDev stdDevRate = null;

    public RunningStdDev stdDevRate() {
        if (null == stdDevRate) {
            stdDevRate = new RunningStdDev();
            int i = stages.length;
            while (--i >= 0) {
                Number n = (Number) GraphManager.getNota(graphManager, stages[i].stageId, GraphManager.SCHEDULE_RATE,
                                                         1_200);
                RunningStdDev.sample(stdDevRate, n.doubleValue());
            }

        }
        return stdDevRate;
    }

    public boolean checkForException() {
        if (firstException != null) {
        	if (firstException instanceof AssertionError) {
        		throw (AssertionError)firstException;
        	} else {
        		throw new RuntimeException(firstException);
        	}
        }
        return true;
    }

    public String name() {
        return name;
    }

    @Override
    public void startup() {
    	if (null==stages) {
    		return;
    	}
    	modificationLock.lock(); //hold the lock

        startupAllStages(stages.length, globalStartupLockCheck);
       
        setupHousekeeping();

    }
    
    public void startup(boolean watchForHang) {
    	if (null==stages) {
    		return;
    	}

    	if (!watchForHang) {
    		startup();
    	} else {
    		
	    	modificationLock.lock(); //hold the lock

	        startupAllStages(stages.length, watchForHang);
	        logger.info("wait for collection of housekeeping data");
	        setupHousekeeping();
	        logger.info("finished startup");
    	}
    }

	private void setupHousekeeping() {
		producersIdx = buildProducersList(0, 0, graphManager, stages);
        producerInputPipes = buildProducersPipes(0, 0, 1, producersIdx, stages, graphManager);
        producerInputPipeHeads = new long[producerInputPipes.length];

        inputPipes = buildInputPipes(0, 0, 1, stages, graphManager);
        inputPipeHeads = new long[inputPipes.length];

        syncInputHeadValues(producerInputPipes, producerInputPipeHeads);
        syncInputHeadValues(inputPipes, inputPipeHeads);
	}

    private static void syncInputHeadValues(Pipe[] pipes, long[] heads) {
        int i = pipes.length;
        while (--i >= 0) {//keep these so we know that it has changed and there is new content
            heads[i] = Pipe.headPosition(pipes[i]);
        }
    }

    private static boolean isSyncInputHeadValues(Pipe[] pipes, long[] heads) {
        int i = pipes.length;
        while (--i >= 0) {//keep these so we know that it has changed and there is new content
            if (heads[i] != Pipe.headPosition(pipes[i])) {
                return false;
            }
        }
        return true;
    }

    private static int[] buildProducersList(int count, int idx, final GraphManager graphManager, PronghornStage[] stages) {

        //skip over the non producers
        while (idx < stages.length) {

            if (null != GraphManager.getNota(graphManager, stages[idx].stageId, GraphManager.PRODUCER, null) ||
                    (0 == GraphManager.getInputPipeCount(graphManager, stages[idx]))) {
                int[] result = buildProducersList(count + 1, idx + 1, graphManager, stages);
                result[count] = idx;
                return result;
            }

            idx++;
        }

        return new int[count];

    }

    private static Pipe[] buildProducersPipes(int count, int indexesIdx, int outputIdx, final int[] indexes, final PronghornStage[] stages, final GraphManager graphManager) {

        while (indexesIdx < indexes.length) {

            int outputCount = GraphManager.getOutputPipeCount(graphManager, stages[indexes[indexesIdx]].stageId);
            while (outputIdx <= outputCount) {

                Pipe pipe = GraphManager.getOutputPipe(graphManager, stages[indexes[indexesIdx]], outputIdx);

                //is the consumer of this pipe inside the graph?
                int consumerId = GraphManager.getRingConsumerId(graphManager, pipe.id);

                int k = stages.length;
                while (--k >= 0) {
                    if (stages[k].stageId == consumerId) {

                        Pipe[] result = buildProducersPipes(count + 1, indexesIdx, outputIdx + 1, indexes, stages,
                                                            graphManager);
                        result[count] = pipe;
                        return result;

                    }
                }
                outputIdx++;
            }
            outputIdx = 1;
            indexesIdx++;
        }
        return new Pipe[count];

    }

    private static Pipe[] buildInputPipes(int count, int stageIdx, int inputIdx, final PronghornStage[] stages, final GraphManager graphManager) {

        while (stageIdx < stages.length) {

            int inputCount = GraphManager.getInputPipeCount(graphManager, stages[stageIdx]);
            while (inputIdx <= inputCount) {

                Pipe pipe = GraphManager.getInputPipe(graphManager, stages[stageIdx], inputIdx);

                int producerId = GraphManager.getRingProducerId(graphManager, pipe.id);

                boolean isFromOutside = true;
                int k = stages.length;
                while (--k >= 0) {
                    if (stages[k].stageId == producerId) {
                        isFromOutside = false;
                        break;
                    }
                }
                if (isFromOutside) {
                    Pipe[] result = buildInputPipes(count + 1, stageIdx, inputIdx + 1, stages, graphManager);
                    result[count] = pipe;
                    return result;

                }

                inputIdx++;
            }
            inputIdx = 1;
            stageIdx++;
        }
        return new Pipe[count];

    }


    /**
     * Stages have unknown dependencies based on their own internal locks and the pipe usages.  As a result we do not
     * know the right order for starting them. 
     */
    private void startupAllStages(final int stageCount, boolean log) {

        int j;
        //to avoid hang we must init all the inputs first
        j = stageCount;
        while (--j >= 0) {
            //this is a half init which is required when loops in the graph are discovered and we need to initialized cross dependent stages.
            if (null != stages[j]) {
                GraphManager.initInputPipesAsNeeded(graphManager, stages[j].stageId);
            }
        }

        int unInitCount = stageCount;

        while (unInitCount > 0) {

            j = stageCount;
            while (--j >= 0) {
                final PronghornStage stage = stages[j];

                if (null != stage && !GraphManager.isStageStarted(graphManager, stage.stageId)) {

                    GraphManager.initAllPipes(graphManager, stage.stageId);

                    try {
                    	if (log) {
                    		logger.info("waiting for startup of {}", stage);
                    	}

                    	long start = 0;
        		    	if (recordTime) {
        		    		start = System.nanoTime();	
        		    	}
        		    	
                        setCallerId(stage.boxedStageId);
                        stage.startup();
                        clearCallerId();
                        
        				if (recordTime) {
        					final long now = System.nanoTime();		        
        		        	long duration = now-start;
        		 			GraphManager.accumRunTimeNS(graphManager, stage.stageId, duration, now);
        				}
                        if (log) {
                        	logger.info("finished startup of {}", stage);
                        }
                        
                        //client work is complete so move stage of stage to started.
                        GraphManager.setStateToStarted(graphManager, stage.stageId);
                        unInitCount--;
                    } catch (Exception t) {
                        recordTheException(stage, t, this);
                        try {
                            if (null != stage) {
                                setCallerId(stage.boxedStageId);
                                GraphManager.shutdownStage(graphManager, stage);
                                clearCallerId();
                            }
                        } catch (Exception tx) {
                            recordTheException(stage, tx, this);
                        } finally {
                            if (null != stage) {
                                GraphManager.setStateToShutdown(graphManager,
                                                                stage.stageId); //Must ensure marked as terminated
                            }
                        }
                        
                        for(int k=j+1;j<stageCount;j++) {
                            final PronghornStage stage2 = stages[k];
                            if (null != stage2 && GraphManager.isStageStarted(graphManager, stage2.stageId)) {
                            	stage2.requestShutdown();
                            }
                        }
                        this.shutdown();
                        return;
                    }
                }
            }
        }


        rates = new long[stageCount + 1];
        lastRun = new long[stageCount + 1];


        int idx = stageCount;
        while (--idx >= 0) {
            final PronghornStage stage = stages[idx];

            //determine the scheduling rules
            if (null == GraphManager.getNota(graphManager, stage, GraphManager.UNSCHEDULED, null)) {

                Object value = GraphManager.getNota(graphManager, stage, GraphManager.SCHEDULE_RATE, Long.valueOf(0));
                long rate = value instanceof Number ? ((Number) value).longValue() : null == value ? 0 : Long.parseLong(
                        value.toString());

                //System.out.println("NTS schedule rate for "+stage+" is "+value);

                if (0 == rate) {
                    //DEFAULT, RUN IN TIGHT LOOP
                    rates[idx] = 0;
                    lastRun[idx] = 0;
                } else {
                    //SCHEDULE_rate, RUN EVERY rate ns
                    rates[idx] = rate;
                    if (rate > maxRate) {
                        maxRate = rate;
                    }
                    lastRun[idx] = 0;
                }
            } else {
                //UNSCHEDULED, NEVER RUN
                rates[idx] = -1;
                lastRun[idx] = 0;
            }
        }

    }

    public long nextRun() {
        return nextRun;
    }

    // Pre-allocate startup information.
    // this value is continues to keep time across calls to run.
    private long blockStartTime = System.nanoTime();
    
    private int platformThresholdForSleep = 0;

    private ReentrantLock modificationLock = new ReentrantLock();

    @Override
    public void run() {
    	try {
    		playScript(this);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();						
			return;
		}
    }

	public static void playScript(ScriptedNonThreadScheduler that) throws InterruptedException {

			assert(null != that.shutdownRequested) : "startup() must be called before run.";
	
			if (that.modificationLock.hasQueuedThreads()) {
				that.modificationLock.unlock();
				Thread.yield();//allow for modification				
				that.modificationLock.lock();				
			}			
			
		        //play the script
	   			int scheduleIdx = 0;
				while (scheduleIdx < that.schedule.script.length) {
		  
								long now = System.nanoTime();
								final long wait = that.blockStartTime - now;
								assert(wait<=that.schedule.commonClock) : "wait for next cycle was longer than cycle definition";
						
								if (Thread.currentThread().getPriority()==Thread.MAX_PRIORITY) {
									if (wait>200) { //TODO: this is a hack test for now...
										//System.err.println(wait);
										Thread.yield();
									}
								} else {
								
									if (wait > 0) {
										that.waitForBatch(wait, that.blockStartTime);
									} else {
										//System.err.println("called yield to avoid tight spin");
										Thread.yield();
										//may be over-scheduled but may be spikes from the OS or GC.
									}
								}
								
								if (null!=that.sleepETL) {
									ElapsedTimeRecorder.record(that.sleepETL, System.nanoTime()-now);
								}
								
								//NOTE: this moves the blockStartTime forward by the common clock unit.
								scheduleIdx = that.runBlock(scheduleIdx, that.schedule.script, that.stages, that.graphManager, that.recordTime);
						
					
		        }
	
		
    	//this must NOT be inside the lock because this visit can cause a modification
		//to this same scheduler which will require the lock.
    	if (null!=that.checksForLongRuns && System.nanoTime()>that.nextLongRunningCheck) {
    		
    		long now = System.nanoTime();
    		//NOTE: this is only called by 1 of the nonThreadSchedulers and not often
    		GraphManager.visitLongRunningStages(that.graphManager, that.checksForLongRuns );
		//////
    		that.nextLongRunningCheck = System.nanoTime() + that.longRunningCheckFreqNS;
    		
    		long duration = System.nanoTime()-now;
    		logger.info("Thread:{} checking for long runs, took {}",that.name,duration);
    		
    	}
	
	}

	/////////////////
	//members for automatic switching of timer from fast to slow 
	//based on the presence of work load
	long totalRequiredSleep = 0;
	long noWorkCounter = 0;
	/////////////////
	
	private void waitForBatch(long wait, long blockStartTime) throws InterruptedException {

		assert(wait<=schedule.commonClock) : "wait for next cycle was longer than cycle definition";
		
		////////////////
		//without any delays the telemetry is likely to get backed up
		////////////////
		
		
		///////////////////////////////
		//this section is only for the case when we need to wait long
		//it is run it often dramatically lowers the CPU usage due to parking
		//using many small yields usage of this is minimized.
		//////////////////////////////
		totalRequiredSleep+=wait;
		long a = (totalRequiredSleep/1_000_000L);
		long b = (totalRequiredSleep%1_000_000L);
		
		if (a>0) {
			
			//logger.info("waiting {} ns and the clock rate is {} added wait {} ",totalRequiredSleep,schedule.commonClock,wait);
			
			long now = System.nanoTime();
			Thread.yield();
			Thread.sleep(a,(int)b);
			long duration = System.nanoTime()-now;
			totalRequiredSleep -= (duration>0?duration:(a*1_000_000));
		} 
		//////////////////////////////
		//////////////////////////////
				
		automaticLoadSwitchingDelay();

	}

	private void automaticLoadSwitchingDelay() {
		if (totalRequiredSleep<200) {//in ns 
			return;//nothing to do;
		}
		
		accumulateWorkHistory();
			
		//if we have over 1000 cycles of non work found then
		//drop CPU usage to greater latency mode since we have no work
		//once work appears stay engaged until we again find 1000 
		//cycles of nothing to process, for 40mircros with is 40 ms switch.
		if (noWorkCounter<1000 || deepSleepCycleLimt<=0) {//do it since we have had recent work
			
			long nowMS = System.nanoTime()/1_000_000l;
			
			if (0!=(nowMS&7)) {// 1/8 of the time every 1 ms we take a break for task manager

				while (totalRequiredSleep>400) {
					long now = System.nanoTime();
					if (totalRequiredSleep>500_000) {
						LockSupport.parkNanos(totalRequiredSleep);
					} else {
						Thread.yield();
					}
					long duration = System.nanoTime()-now;
					if (duration<0) {
						duration = 1;
					}
					totalRequiredSleep-=duration;
				}
			} else {
				//let the task manager know we are not doing work.
				long now = System.nanoTime();
				LockSupport.parkNanos(totalRequiredSleep);
				long duration = System.nanoTime()-now;
				//System.err.println(totalRequiredSleep+" vs "+duration);
				if (duration>0) {
					totalRequiredSleep -= duration;
				}
			}
		} else {
			//this is to support deep sleep when it has been a very long time without work.
			
			while ((noWorkCounter > deepSleepCycleLimt)) {
				try {
					Thread.sleep(humanLimitNS/1_000_000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
				//we stay in this loop until we find real work needs to be done
				if (!accumulateWorkHistory()) {
					break;
				}
			}	
		}
	}

	@SuppressWarnings("unchecked")
	private boolean accumulateWorkHistory() {
		boolean hasData=false;
		int p = inputPipes.length;
		//if we have any producers we must not do this!!!
		if (p>0 && producersIdx.length==0) {			
			while (--p>=0) {			
				if (Pipe.contentRemaining(inputPipes[p])>0) {
					hasData=true;
					break;
				}
			}
			if (hasData) {
				noWorkCounter = 0;	
			} else {			
				noWorkCounter++;
			}
			return true; //we had something to count
		} else {
			noWorkCounter = 0;
			return false; //this stage has no inputs and must always be run.
		}
	}
	
	private int runBlock(int scheduleIdx, int[] script, 
			             PronghornStage[] stages,
			             GraphManager gm, final boolean recordTime) {
			
		final long startNow = System.nanoTime();
		boolean shutDownRequestedHere = false;
		int inProgressIdx;
		// Once we're done waiting for a block, we need to execute it!
	
		// If it isn't out of bounds or a block-end (-1), run it!
		while ((scheduleIdx<script.length) && ((inProgressIdx = script[scheduleIdx++]) >= 0)) {
			shutDownRequestedHere = monitoredStageRun(stages, gm, 
	    			recordTime, didWorkMonitor, 
	    			shutDownRequestedHere,
					inProgressIdx);			
		}

		//given how long this took to run set up the next run cycle.
		blockStartTime = startNow+schedule.commonClock;
		
		return detectShutdownScheduleIdx(scheduleIdx, shutDownRequestedHere);
	}

	private final int detectShutdownScheduleIdx(int scheduleIdx, boolean shutDownRequestedHere) {
		// If a shutdown is triggered in any way, shutdown and halt this scheduler.
		return (!(shutDownRequestedHere || shutdownRequested.get())) ? scheduleIdx : triggerShutdown();
	}

	private int triggerShutdown() {
		if (!shutdownRequested.get()) {
			shutdown();
		}
		return Integer.MAX_VALUE;
	}

	private boolean monitoredStageRun(PronghornStage[] stages, GraphManager gm, final boolean recordTime,
			final DidWorkMonitor localDidWork, boolean shutDownRequestedHere, int inProgressIdx) {
		long start = 0;	
		long SLAStart = System.currentTimeMillis();		    	
		start = System.nanoTime();
		DidWorkMonitor.begin(localDidWork,start);
				    	
		PronghornStage stage = stages[inProgressIdx];
		
		if (!GraphManager.isStageShuttingDown(stateArray, stage.stageId)) {

		        if (debugNonReturningStages) {
		            logger.info("begin run {}", stage);///for debug of hang
		        }
		        setCallerId(stage.boxedStageId);
		        
		        runStage(stage);
		        
		        clearCallerId();

		        if (debugNonReturningStages) {
		            logger.info("end run {}", stage);
		        }
		} else {
		    processShutdown(gm, stage);
		    shutDownRequestedHere = true;
		}
		 
			        
		if (!DidWorkMonitor.didWork(localDidWork)) {
		} else {
			final long now = System.nanoTime();		        
			long duration = now-start;
			
			if (duration <= sla[inProgressIdx]) {
			} else {
				reportSLAViolation(stages, gm, inProgressIdx, SLAStart, duration);		    		
			}
			if (recordTime) {		
				if (!GraphManager.accumRunTimeNS(gm, stage.stageId, duration, now)){
					if (!shownLowLatencyWarning) {
						shownLowLatencyWarning = true;
						logger.warn("\nThis platform is unable to measure ns time slices due to OS or hardware limitations.\n Work was done by an actor but zero time was reported.\n");
					}
				}
			}
		}
		return shutDownRequestedHere;
	}

	private final void runStage(PronghornStage stage) {
		try {
			stage.run();
		} catch (Exception e) {			
			recordTheException(stage, e, this);
			//////////////////////
			//check the output pipes to ensure no writes are in progress
			/////////////////////
			int c = GraphManager.getOutputPipeCount(graphManager, stage.stageId);
			for(int i=1; i<=c; i++) {		
				if (Pipe.isInBlobFieldWrite((Pipe<?>) GraphManager.getOutputPipe(graphManager, stage.stageId, i))) {
					//we can't recover from this exception because write was left open....
					shutdown();	
					break;
				}
			}
			
		}
	}

	private void reportSLAViolation(PronghornStage[] stages, GraphManager gm, int inProgressIdx, long SLAStart,
			long duration) {
		String stageName = stages[inProgressIdx].toString();
		int nameLen = stageName.indexOf('\n');
		if (-1==nameLen) {
			nameLen = stageName.length();
		}
		Appendables.appendEpochTime(
				Appendables.appendEpochTime(
						Appendables.appendNearestTimeUnit(System.err.append("SLA Violation: "), duration)
						.append(" ")
						.append(stageName.subSequence(0, nameLen))
						.append(" ")
						,SLAStart).append('-')
				,System.currentTimeMillis())
		.append(" ").append(gm.name).append("\n");
	}

	public boolean isContentForStage(PronghornStage stage) {
		int inC = GraphManager.getInputPipeCount(graphManager, stage.stageId);
		for(int k = 1; k <= inC; k++) {
			if (Pipe.contentRemaining((Pipe<?>)
					GraphManager.getInputPipe(graphManager, stage.stageId, k)
					) != 0) {
				return true;
			}
		}
		return false;
	}



	private static boolean processShutdown(GraphManager graphManager, PronghornStage stage) {
		if (!GraphManager.isStageTerminated(graphManager, stage.stageId)) {
		    GraphManager.shutdownStage(graphManager, stage);
		    GraphManager.setStateToShutdown(graphManager, stage.stageId);
		}
		return false;
	}

    private Object key = "key";
    
    @Override
    public void shutdown() {
    	
    	
        if (null!=stages && shutdownRequested.compareAndSet(false, true)) {

        	synchronized(key) {
        		
        		if (null!=sleepETL) {        			
        			System.err.println("sleep: "+graphManager.name+" "+name);
        			System.err.println(sleepETL.toString());
        		}
        		
        		boolean debug = false;
                if (debug) {	
        	        System.err.println();
        	        System.err.println("----------full stages ------------- clock:"+schedule.commonClock);
        	        for(int i = 0; i<stages.length; i++) {
        	        	
        	        	StringBuilder target = new StringBuilder();
        	    		target.append("full stages "+stages[i].getClass().getSimpleName()+":"+stages[i].stageId);
        	    		target.append("  inputs:");
        	    		GraphManager.appendInputs(graphManager, target, stages[i]);
        	    		target.append(" outputs:");
        	    		GraphManager.appendOutputs(graphManager, target, stages[i]);
        	    		        		
        	    		System.err.println("   "+target);
        	        	
        	        }
                }
            }
        	
        	
        	
            int s = stages.length;
            while (--s >= 0) {
                //ensure every non terminated stage gets shutdown called.
                if (null != stages[s] && !GraphManager.isStageTerminated(graphManager, stages[s].stageId)) {
                    GraphManager.shutdownStage(graphManager, stages[s]);
                    GraphManager.setStateToShutdown(graphManager, stages[s].stageId);
                    //System.err.println("terminated "+stages[s]+"  "+GraphManager.isStageTerminated(graphManager, stages[s].stageId));
                }
            }

            PronghornStage temp = lastRunStage;
            if (null != temp) {
                logger.info("ERROR: this stage was called but never returned {}", temp);
            }
        }
        
    }

    public static boolean isShutdownRequested(ScriptedNonThreadScheduler nts) {
        return nts.shutdownRequested.get();
    }

    @Override
    public void awaitTermination(long timeout, TimeUnit unit, Runnable clean, Runnable dirty) {
        if (awaitTermination(timeout, unit)) {
            clean.run();
        } else {
            dirty.run();
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {

        if (!shutdownRequested.get()) {
            throw new UnsupportedOperationException("call shutdown before awaitTerminination");
        }
        long limit = System.nanoTime() + unit.toNanos(timeout);

        if (isRunning.get() != 2) {
            //wait until we get shutdown or timeout.
            while (!isRunning.compareAndSet(0, 2)) {
                Thread.yield();
                if (System.nanoTime() > limit) {
                    return false;
                }
            }
        }

        int s = stages.length;
        while (--s >= 0) {
            PronghornStage stage = stages[s];

            if (null != stage && !GraphManager.isStageTerminated(graphManager, stage.stageId)) {
                GraphManager.shutdownStage(graphManager, stage);
                GraphManager.setStateToShutdown(graphManager, stage.stageId);
            }
        }

        return true;

    }

    @Override
    public boolean TerminateNow() {
        shutdown();
        return true;
    }

    private static void recordTheException(final PronghornStage stage, Exception e, ScriptedNonThreadScheduler that) {
        synchronized (that) {
            if (null == that.firstException) {
                that.firstException = e;
            }
        }

        GraphManager.reportError(that.graphManager, stage, e, logger);
    }
    
    

	public static int[] buildSkipScript(ScriptedSchedule schedule, 
			                            GraphManager gm, 
			                            PronghornStage[] stages,
			                            int[] script) {
		
		int[] skipScript = new int[script.length];
		int lastPointIndex = 0;
		
		for(int idx = 0; idx<script.length; idx++) {
			if (script[idx] != -1) {
				int stageId = stages[schedule.script[idx]].stageId;

				final int inC1 = GraphManager.getInputPipeCount(gm, stageId);
				if ((inC1 == 0) || (GraphManager.hasNota(gm, stageId, GraphManager.PRODUCER))) {
					//producer, all producers must always be run.
					skipScript[idx] = -2;
					lastPointIndex = idx+1; //this starts a new run from here.
					continue;
				} else {
					for(int k = 1; k <= inC1; k++) {
						int id = GraphManager.getInputPipe(gm, stageId, k).id;
						//this id MUST be found as one of the previous outs
						//if not found this is a new point
						if (!isInputLocal(lastPointIndex, idx, gm, stages, script, id)) {
							lastPointIndex = idx;
						}
					}
				}
				
				//count up how long this run is at the head position of the run
				if (lastPointIndex!=-1) {
					skipScript[lastPointIndex]++;
				}
				
			} else {
				skipScript[idx] = -1;
				lastPointIndex = idx+1;
			}
			
			
		}
		return skipScript;
	}

	private static boolean isInputLocal(int startIdx,
										int stopIdx, 
			                            GraphManager gm, 
			                            PronghornStage[] stages, 
			                            int[] script,
			                            int goalId) {
		//scan for an output which matches this goal Id
		
		for(int i = startIdx; i<=stopIdx; i++) {
			int stageId = stages[script[i]].stageId;
			int outC = GraphManager.getOutputPipeCount(gm, stageId);
			for(int k = 1; k <= outC; k++) {
				if (goalId == GraphManager.getOutputPipe(gm, stageId, k).id) {
					return true;
				}
			}
		}
		return false;
	}

	////////////////////
	//these elapsed time methods are for thread optimization
	///////////////////
	public long nominalElapsedTime(GraphManager gm) {
		long sum = 0;
		int i = stages.length;
		while (--i>=0) {
			sum += GraphManager.elapsedAtPercentile(gm,stages[i].stageId, .80f);
		}
		return sum;
	}
	
	//if this stage has no inputs which came from this
	//same array of stages then yes it has no local inputs
	private boolean hasNoLocalInputs(int idx) {
		
		int id = stages[idx].stageId;
		
		int count = GraphManager.getInputPipeCount(graphManager, id);
		for(int c=1; c<=count; c++) {
			int inPipeId = GraphManager.getInputPipeId(graphManager, id, c);			
			int producerId = GraphManager.getRingProducerStageId(graphManager, inPipeId);
			
			int w = idx;
			while (--w>=0) {
				if (stages[w].stageId == producerId) {
					return false;
				}
			}
		}		
		return true;
	}
	
	public int recommendedSplitPoint(GraphManager gm) {
		
		long aMax = -1;
		int aMaxIdx = -1;
		
		long bMax = -1;
		int bMaxIdx = -1;
				
		int i = stages.length;
		while (--i>=0) {
			long elap = GraphManager.elapsedAtPercentile(gm,stages[i].stageId, .80f);
		
			//find the general largest
			if (elap > aMax) {
				aMax = elap;
				aMaxIdx = i;
			}
			
			//find the largest of those which have no local input pipes
			if ((elap > bMax) && hasNoLocalInputs(i)) {
				bMax = elap;
				bMaxIdx = i;
			}			
		}		
		//return the natural split if it was found else return the large one.
		return (-1 != bMaxIdx) ? bMaxIdx : aMaxIdx;				
	}
	///////////////////
	///////////////////
	
	public ScriptedNonThreadScheduler splitOn(int idx) {
		assert(idx<stages.length);
		assert(idx>=0);
		
		
		
		//stop running while we do the split.
		modificationLock.lock();
		//all running of this script is not blocked until we finish this modification.
		try {
			
			ScriptedNonThreadScheduler result = null;
			
			PronghornStage[] localStages;
			PronghornStage[] resultStages;
			if (reverseOrder) {
				resultStages = Arrays.copyOfRange(stages, 0, idx+1);
				localStages = Arrays.copyOfRange(stages, idx+1, stages.length);
				assert(resultStages.length>0);
				assert(localStages.length>0);
			} else {
				resultStages = Arrays.copyOfRange(stages, idx, stages.length);
				localStages = Arrays.copyOfRange(stages, 0, idx);
				assert(resultStages.length>0) : "stages "+stages.length+" at "+idx;
				assert(localStages.length>0);
			}

			result = new ScriptedNonThreadScheduler(graphManager, reverseOrder, resultStages);
			buildSchedule(graphManager, localStages, reverseOrder);
	        setupHousekeeping();

	        result.setupHousekeeping();
	        
			return result;
		} finally {
			modificationLock.unlock();
		}
	}


	
	
}
