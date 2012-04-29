package org.globus.cog.abstraction.coaster.service.job.manager;

import java.io.File;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.globus.cog.abstraction.coaster.rlog.RemoteLogger;
import org.globus.cog.abstraction.coaster.service.CoasterService;
import org.globus.cog.abstraction.coaster.service.RegistrationManager;
import org.globus.cog.abstraction.impl.common.AbstractionFactory;
import org.globus.cog.abstraction.impl.common.execution.WallTime;
import org.globus.cog.abstraction.interfaces.ExecutionService;
import org.globus.cog.abstraction.interfaces.Task;
import org.globus.cog.karajan.workflow.service.channels.ChannelContext;
import org.globus.cog.karajan.workflow.service.channels.KarajanChannel;

public class BlockQueueProcessor
extends AbstractQueueProcessor
implements RegistrationManager, Runnable {
    public static final Logger logger = Logger.getLogger(BlockQueueProcessor.class);

    private Settings settings;

    private final Map<Integer, List<Job>> tl;

    /**
       Jobs not yet moved to holding because the allocator was
       planning while it was enqueued
     */
    private final List<Job> incoming;

    /**
       Jobs not moved to queued - they may not fit into existing
       blocks
     */
    private SortedJobSet holding;

    /**
       Jobs that either fit into existing Blocks or were enqueued
       since the last updatePlan
     */
    private final SortedJobSet queued;

    /* Need to keep an account of running jobs in order to correctly
     * make sense of the allocated size. If running jobs are not
     * considered, it can appear that the allocated size is much
     * larger than the required size, and blocks get shut down
     * inappropriately.
     */
    private final JobSet running;

    private List<Integer> sums;
    private final Map<String, Block> blocks;

    private double allocsize;

    double medianSize, tsum;

    private File script;

    private String id;

    private static final DateFormat DDF = new SimpleDateFormat("MMdd-mmhhss");

    private BQPMonitor monitor;

    private ChannelContext clientChannelContext;

    private boolean done;

    private final Metric metric;

    private final RemoteLogger rlogger;

    /**
       Formatter for time-based variables in whole seconds
     */
    private static final NumberFormat SECONDS =
    	new DecimalFormat("0");

    /**
       Formatter for time-based variables to thousandth of second
    */
    private static final NumberFormat SECONDS_3 =
    	new DecimalFormat("0.000");

    public BlockQueueProcessor(Settings settings) {
        super("Block Queue Processor");
        this.settings = settings;
        holding = new SortedJobSet();
        blocks = new TreeMap<String, Block>();
        tl = new HashMap<Integer, List<Job>>();
        id = DDF.format(new Date());
        incoming = new ArrayList<Job>();
        metric = new OverallocatedJobDurationMetric(settings);
        queued = new SortedJobSet(metric);
        running = new JobSet(metric);
        rlogger = new RemoteLogger();
        if (logger.isInfoEnabled()) {
            logger.info("Starting... id=" + id);
        }
    }

    public Metric getMetric() {
        return metric;
    }

    @Override
    public void run() {
        try {
            script = ScriptManager.writeScript();
            int planTimeMillis = 1;
            while (!done) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Holding queue job count: " +
                             holding.size());
                }
                planTimeMillis = updatePlan();
                double planTime = ((double) planTimeMillis) / 1000;
                if (logger.isDebugEnabled()) {
                    logger.debug("Planning time (seconds): " +
                             SECONDS_3.format(planTime));
                }
                if (holding.size() + incoming.size() == 0) {
                    planTimeMillis = 100;
                }
                synchronized (incoming) {
                    incoming.wait(Math.min(planTimeMillis * 20, 10000) + 1000);
                }
            }
        }
        catch (Exception e) {
            CoasterService.error(13, "Exception caught in block processor", e);
        }
    }

    public File getScript() {
        return script;
    }

    public void add(int time, List<Job> jobs) {
        time += 1;
        tl.put(time, jobs);
        if (time == 0) {
            enqueue(jobs);
        }
    }

    @Override
    public void enqueue(Task t) {
        enqueue1(t);
    }

    public void enqueue1(Task t) {
        Job j = new Job(t);
        if (checkJob(j)) {
            synchronized (incoming) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Got job with walltime = " + j.getMaxWallTime());
                }
                incoming.add(j);
            }
        }
    }

    private boolean checkJob(Job job) {
        if (job.getMaxWallTime().getSeconds() > settings.getMaxtime() - settings.getReserve().getSeconds()) {
            job.fail("Job walltime > maxTime - reserve (" + 
                    WallTime.format("hms", job.getMaxWallTime().getSeconds()) + " > " + 
                    WallTime.format("hms", settings.getMaxtime() - settings.getReserve().getSeconds()) + ")", null);
            return false;
        }
        else {
            return true;
        }
    }

    public void enqueue(List<Job> jobs) {
        synchronized (incoming) {
            incoming.addAll(jobs);
        }
    }

    private void queue(Job job) {
    	synchronized (queued) {
    	    queued.add(job);
            queued.notify();
        }
    }

    public void waitForJobs() throws InterruptedException {
        synchronized (queued) {
            queued.wait(1000);
        }
    }

    private void cleanDoneBlocks() {
        int count = 0;
        List<Block> snapshot;
        synchronized (blocks) {
            snapshot = new ArrayList<Block>(blocks.values());
        }
        for (Block b : snapshot) {
            if (b.isDone()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Cleaning done block " + b);
                }
                b.shutdown(false);
                count++;
            }
        }
        if (count > 0) {
            logger.debug("Cleaned " + count + " done blocks");
        }
    }

    private double lastAllocSize;

    private void updateAllocatedSize() {
        synchronized (blocks) {
            allocsize = 0;
            for (Block b : blocks.values()) {
                allocsize += b.sizeLeft();
            }
            if (allocsize != lastAllocSize) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Updated allocsize: " + allocsize);
                }
            }
            lastAllocSize = allocsize;
        }
    }

    private boolean fits(Job j) {
        synchronized (blocks) {
            for (Block b : blocks.values()) {
                if (!b.isSuspended() && b.fits(j)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void addBlock(Block b) {
        synchronized (blocks) {
            blocks.put(b.getId(), b);
        }
        b.start();
    }

    private void queueToExistingBlocks() {
        List<Job> remove = new ArrayList<Job>();
        double runningSize = getRunningSizeLeft();
        int count = 0;
        for (Job j : holding) {
            if (allocsize - queued.getJSize() - runningSize > metric.getSize(j) && fits(j)) {
                queue(j);
                remove.add(j);
                count++;
            }
        }
        if (count > 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("Queued " + count + " jobs to existing blocks");
            }
        }
        holding.removeAll(remove);
    }

    private void requeueNonFitting() {
        int count = 0;
        double runningSize = getRunningSizeLeft();
        if (logger.isDebugEnabled()) {
            logger.debug("allocsize = " + allocsize +
                     ", queuedsize = " + queued.getJSize() +
                     ", running = " + runningSize +
                     ", qsz = " + queued.size());
        }
        while (allocsize - queued.getJSize() - runningSize < 0) {
            Job j = queued.removeOne(TimeInterval.FOREVER,
                                     Integer.MAX_VALUE);
            if (j == null) {
                if (queued.getJSize() > 0) {
                    CoasterService.error(19, "queuedsize > 0 but no job dequeued. Queued: " + queued,
                        new Throwable());
                }
                else if (allocsize - getRunningSizeLeft() < 0) {
                    warnAboutWalltimes(running);
                }
            }
            holding.add(j);
            count++;
        }
        if (count > 0) {
            if (logger.isInfoEnabled()) {
                logger.info("Requeued " + count + " non-fitting jobs");
            }
        }
    }
    
    private void warnAboutWalltimes(Iterable<Job> set) {
        synchronized(set) {
            for (Job r : set) {
                if (r.getMaxWallTime().isLessThan(Time.now().subtract(r.getStartTime()))) {
                    Task t = r.getTask();
                    if (t.getAttribute("#warnedAboutWalltime") == null) {
                        logger.warn("The following job exceeded its walltime: " + 
                            t.getSpecification());
                        t.setAttribute("#warnedAboutWalltime", Boolean.TRUE);
                    }
                }
            }
        }
    }

    private double getRunningSizeLeft() {
        synchronized(running) {
            return running.getSizeLeft();
        }
    }

    private void computeSums() {
        sums = new ArrayList<Integer>(holding.size());
        sums.add(0);
        int ps = 0;
        for (Job j : holding) {
            ps += metric.getSize(j);
            sums.add(new Integer(ps));
        }
    }

    /**
       Total request size in seconds
     */
    private int computeTotalRequestSize() {
        double sz = 0;
        for (Job j : holding) {
            sz += metric.desiredSize(j);
        }
        if (sz > 0) {
            if (sz < 1)
                sz = 1;
            if (logger.isInfoEnabled()) {
                logger.info("Jobs in holding queue: " + holding.size());
            }
            String s = SECONDS.format(sz);
            if (logger.isInfoEnabled()) {
                logger.info("Time estimate for holding queue (seconds): " + s);
            }
        }
        return (int) sz;
    }

    /**
       @return Time overallocation in seconds
     */
    public int overallocatedSize(Job j) {
        return overallocatedSize(j, settings);
    }

    /**
       @return Time overallocation in seconds
     */
    public static int overallocatedSize(Job j, Settings settings) {
        int walltime = (int) j.getMaxWallTime().getSeconds();
        return overallocatedSize(walltime, settings);
    }

    /**
       @param walltime in seconds
       @return Time overallocation in seconds
     */
    private static int overallocatedSize(int walltime, Settings settings) {
        double L = settings.getLowOverallocation();
        double H = settings.getHighOverallocation();
        double D = settings.getOverallocationDecayFactor();
        return (int) (walltime * ((L - H) * Math.exp(-walltime * D) + H));
    }

    private int sumSizes(int start, int end) {
        int sumstart = sums.get(start);
        int sumend = sums.get(end);
        return sumend - sumstart;
    }

    /**
     * Suspends blocks while the total size left in the blocks is
     * larger than the amount of size needed.
     *
     * Blocks with the least amount of size left are suspended first.
     *
     * Blocks are only suspended if both the above size condition is true
     * and they have not see any work withing a certain time interval. This
     * is done to dampen the effects of transients in the submission
     * pattern.
     *
     * Once a block is suspended, it will finish its current tasks and then
     * shut down.
     *
     */
    protected void removeIdleBlocks() {
        ArrayList<Block> sorted;

        synchronized (blocks) {
            sorted = new ArrayList<Block>(blocks.values());
            Collections.sort(sorted, new Comparator<Block>() {
                public int compare(Block b1, Block b2) {
                    double s1 = b1.sizeLeft();
                    double s2 = b2.sizeLeft();
                    if (s1 < s2) {
                        return -1;
                    }
                    else {
                        return 1;
                    }
                }
            });
        }

        double needed = queued.getJSize() + running.getSize();

        double sum = 0;
        for (Block b : sorted) {
            if (sum >= needed
                    && !b.isSuspended()
                    && (System.currentTimeMillis() - b.getLastUsed()) > Block.SUSPEND_SHUTDOWN_DELAY) {
                b.suspend();
            }
            sum += b.sizeLeft();
        }
    }

    /**
       Move Jobs from {@link holding} to {@link queued} by
       allocating new Blocks for them
     */
    private void allocateBlocks(double tsum) {
        List<Job> remove = new ArrayList<Job>();
        // Calculate chunkOfBlocks: how many blocks we may allocate
        //     in this particular call to allocateBlocks()
        int maxBlocks = settings.getMaxBlocks();
        double fraction = settings.getAllocationStepSize();
        int chunkOfBlocks =
            (int) Math.ceil((maxBlocks - blocks.size()) * fraction);

        // Last job queued to last new Block
        int last = 0;
        Iterator<Job> lastI = holding.iterator();
        // Index through holding queue
        int index = 0;
        Iterator<Job> indexI = holding.iterator();
        // Number of new Blocks allocated in this call
        int newBlocks = 0;
        
        int added = 0;

        // get the size (w * h) for the current block by 
        // dividing the total required size (tsum) by the number
        // of slots used in this round and scaling based on the spread.
        //
        // i.e.  0 spread            max spread   
        //    ________________                 ____
        //    |  |  |  |  |  |              ___|  |
        //    |  |  |  |  |  |           ___|  |  |
        //    |  |  |  |  |  |        ___|  |  |  |
        //    |  |  |  |  |  |     ___|  |  |  |  |
        //    |__|__|__|__|__|     |__|__|__|__|__|
        // (where the total area =~ tsum in both cases)
        double size = metric.blockSize(newBlocks, chunkOfBlocks, tsum);

        String s = SECONDS.format(tsum);
        if (logger.isInfoEnabled()) {
            logger.info("Allocating blocks for a total walltime of: " + s + "s");
        }

        while (indexI.hasNext() && newBlocks < chunkOfBlocks) {
            Job job = indexI.next();
            
            int granularity = settings.getNodeGranularity() * settings.getJobsPerNode();
            // true if the number of jobs for this block is a multiple
            // of granularity
            boolean granularityFit = (index - last) % granularity == 0;
            // true if we've reached the end of the job list
            boolean lastChunk = (index == holding.size() - 1);
            // true when the size of jobs from the last allocated one up to the current one
            // are greater than the size (which means we have to start committing jobs to the block)
            boolean sizeFit = false;
            if (!lastChunk) {
                sizeFit = sumSizes(last, index) > size;
            }
            
            // if there are enough jobs and they match the granularity or if these are the last jobs
            if ((granularityFit && sizeFit) || lastChunk) {
                int msz = (int) size;
                // jobs are sorted on walltime, and the last job is the longest,
                // so use that for calculating the overallocation
                int lastwalltime = (int) job.getMaxWallTime().getSeconds();
                int h = overallocatedSize(job);
                
                // the maximum time is a hard limit, so for the maximum useable time
                // the reserve needs to be subtracted
                int maxt =
                  settings.getMaxtime() - (int) settings.getReserve().getSeconds();
                // height must be a multiple of the overallocation of the
                // largest job
                // Is not h <= round(h, lastwalltime) ? -Justin
                //   Yes, it is (see comment in round()) - Mihael
                // h = Math.min(Math.max(h, round(h, lastwalltime)), maxt);
                
                // If h > maxt, should we report a warning/error? -Justin
                //   No. h is the overallocated time (i.e. greater than the
                //   job walltime). So it's acceptable for that to go over maxt.
                //   The error is when walltime > maxt - Mihael
                h = Math.min(round(h, lastwalltime), maxt);
                
                // once we decided on a height, get the width by dividing the total size
                // by the height (and rounding appropriately to fit the granularity), 
                // while making sure that we don't go over maxNodes
                int width =
                        Math.min(round(metric.width(msz, h), granularity),
                                 settings.getMaxNodes()
                                 * settings.getJobsPerNode());
                // while we were shooting to have the number of jobs be a multiple of the
                // width, various constraints might have changed that, so adjust the 
                // number of jobs accordingly
                int r = (index - last) % width;

                if (logger.isInfoEnabled()) {
                	logger.info("\t Considering: " + job);
                	logger.info("\t  Max Walltime (seconds):   " + lastwalltime);
                    logger.info("\t  Time estimate (seconds):  " + h);
                    logger.info("\t  Total for this new Block (est. seconds): " +
                                sumSizes(last, index));
                }

                // read more jobs to fill up the remaining space towards the granularity
                // +-----+     +-----+
                // |xx...|     |xxrrr|
                // |xxxxx| --> |xxxxx|
                // |xxxxx|     |xxxxx|
                // |xxxxx|     |xxxxx|
                // +-----+     +-----+
                
                
                if (r != 0) {
                	// and make sure that there is enough walltime to run the new added jobs
                	// though this is improper: what should be added to h is 
                	// (newLastwalltime - lastwalltime) where newLastwalltime is the walltime
                	// of the i-th job after adding (w - r).
                    h = Math.min(h + lastwalltime, maxt);
                }


                // create the actual block
                Block b = new Block(width, TimeInterval.fromSeconds(h), this);
                
                // now add jobs from holding until the size of the jobs exceeds the
                // size of the block (as given by the metric)
                int ii = last;
                while (lastI.hasNext() && sumSizes(last, ii + 1) <= metric.size(width, h)) {
                    Job j = lastI.next();
                    queue(j);
                    remove.add(j);
                    added++;
                    ii++;
                }
                
                if (logger.isInfoEnabled()) {
                    logger.info("Queued: " + (ii-last) +
                                " jobs to new Block");
                }
                // update index of last added job
                // skip ii - index - 1 jobs since the iterator and "index" are off by one
                // since index only gets updated at the end of the loop
                for (int i = 0; i < ii - index - 1; i++) {
                    indexI.next();
                }
                index = ii - 1;
                // commit the block
                addBlock(b);
                last = index + 1;

                newBlocks++;
                size = metric.blockSize(newBlocks, chunkOfBlocks, tsum);
            }
            index++;
        }
        holding.removeAll(remove);
        if (added > 0) {
            if (logger.isInfoEnabled()) {
                logger.info("Added " + added + " jobs to new blocks");
            }
        }
    }

    private boolean first = true;

    private void updateSettings() throws PlanningException {
        if (!first) {
            return;
        }
        if (!holding.isEmpty()) {
            Job j = holding.iterator().next();
            Task t = j.getTask();
            ExecutionService p = (ExecutionService) t.getService(0);
            settings.setServiceContact(p.getServiceContact());
            String jm = p.getJobManager();
            int colon = jm.indexOf(':');
            // remove provider used to bootstrap coasters
            jm = jm.substring(colon + 1);
            colon = jm.indexOf(':');
            if (colon == -1) {
                settings.setProvider(jm);
            }
            else {
                settings.setJobManager(jm.substring(colon + 1));
                settings.setProvider(jm.substring(0, colon));
            }
            if (p.getSecurityContext() != null) {
                settings.setSecurityContext(p.getSecurityContext());
            }
            else {
                try {
                    settings.setSecurityContext(AbstractionFactory.newSecurityContext(settings.getProvider()));
                }
                catch (Exception e) {
                    throw new PlanningException(e);
                }
            }
            if (first) {
                if (logger.isInfoEnabled()) {
                    logger.info("\n" + settings.toString());
                }
                first = false;
            }
        }
    }

    private void commitNewJobs() {
        synchronized (incoming) {
            holding.addAll(incoming);
            if (incoming.size() > 0) {
                if (logger.isInfoEnabled()) {
                    logger.info("Committed " + incoming.size() + " new jobs");
                }
            }
            incoming.clear();
        }
    }

    private long lastUpdate;

    private void updateMonitor() {
        if (settings.isRemoteMonitorEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastUpdate > 10000) {
                if (monitor == null) {
                    monitor = new RemoteBQPMonitor(this);
                }
                monitor.update();
                lastUpdate = now;
            }
        }
    }
    
    /**
      @return Time consumed in milliseconds
     */
    public int updatePlan() throws PlanningException {
        Set<Job> tmp;

        long start = System.currentTimeMillis();

        synchronized(holding) {
            // Move all incoming Jobs to holding
            commitNewJobs();
    
            // Shutdown Blocks that are done
            cleanDoneBlocks();
    
            // Subtract elapsed time from existing allocation
            updateAllocatedSize();
        
            // Move jobs that fit from holding to queued
            queueToExistingBlocks();
    
            // int jss = jobs.size();
            // If queued has too many Jobs, move some back to holding
            requeueNonFitting();
    
            updateSettings();
    
            computeSums();
    
            tsum = computeTotalRequestSize();
    
            if (tsum == 0) {
                removeIdleBlocks();
            }
            else {
                allocateBlocks(tsum);
            }
        }

        updateMonitor();
        
        return (int) (System.currentTimeMillis() - start);
    }

    public Job request(TimeInterval ti, int cpus) {
        Job job = queued.removeOne(ti, cpus);
        if (job == null) {
            synchronized(holding) {
                job = holding.removeOne(ti, cpus);
            }
        }
        
        if (job != null) {
            synchronized(running) {
                running.add(job);
            }
        }
        else {
            if (logger.isDebugEnabled()) {
                logger.debug("request - no job " + ti + ", " + cpus);
            }
        }
        return job;
    }

    public void jobTerminated(Job job) {
        synchronized(running) {
            running.remove(job);
        }
    }

    /**
       Round v up to the next multiple of g
     */
    private int round(int v, int g) {
        // (v % g) < g => x - (v % g) + g > x
        int r = v - (v % g) + g;
        return r;
    }

    // private static int jid;

    public void addToPlannedQueue(Task t) {
        queue(new Job(t));
    }

    @Override
    public void shutdown() {
        shutdownBlocks();
        done = true;
    }

    private void shutdownBlocks() {
        logger.info("Shutting down blocks");
        synchronized (blocks) {
            for (Block b : new ArrayList<Block>(blocks.values())) {
                b.shutdown(true);
            }
        }
    }

    public void blockTaskFinished(Block block) {
        if (logger.isInfoEnabled()) {
            logger.info("Removing block " + block);
        }
        synchronized (blocks) {
            blocks.remove(block.getId());
        }
    }

    public Settings getSettings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    protected Block getBlock(String id) {
        synchronized (blocks) {
            Block b = blocks.get(id);
            if (b != null) {
                return b;
            }
            throw new IllegalArgumentException("No such block: " + id);
        }
    }

    public String registrationReceived(String blockID,
                                       String workerID,
                                       String workerHostname,
                                       ChannelContext channelContext,
                                       Map<String, String> options) {
        return getBlock(blockID).workerStarted(workerID,
                                               workerHostname,
                                               channelContext);
    }

    public String nextId(String id) {
        return getBlock(id).nextId();
    }

    public String getBQPId() {
        return id;
    }

    public void setBQPId(String id) {
        this.id = id;
    }

    public List<Job> getJobs() {
        return holding.getAll();
    }

    public SortedJobSet getQueued() {
        return queued;
    }

    public Map<String, Block> getBlocks() {
        return blocks;
    }

    public void setClientChannelContext(ChannelContext channelContext) {
        this.clientChannelContext = channelContext;
        rlogger.setChannelContext(channelContext);
    }

    public ChannelContext getClientChannelContext() {
        return clientChannelContext;
    }
    
    public boolean clientIsConnected() {
        return clientChannelContext != null;
    }

    public static void main(String[] args) {
        Settings s = new Settings();
        System.out.println(overallocatedSize(1, s));
        System.out.println(overallocatedSize(10, s));
        System.out.println(overallocatedSize(100, s));
        System.out.println(overallocatedSize(1000, s));
        System.out.println(overallocatedSize(3600, s));
        System.out.println(overallocatedSize(10000, s));
        System.out.println(overallocatedSize(100000, s));
    }

    public int getQueueSeq() {
        return queued.getSeq();
    }

    public RemoteLogger getRLogger() {
        return rlogger;
    }

    /**
       Get the KarajanChannel for the worker with given id
     */
    public KarajanChannel getWorkerChannel(String id) {
        return null;
    }

    public void nodeRemoved(Node node) {
    }
}
