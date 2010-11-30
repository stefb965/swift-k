//----------------------------------------------------------------------
//This code is developed as part of the Java CoG Kit project
//The terms of the license can be found at http://www.cogkit.org/license
//This message may not be removed or altered.
//----------------------------------------------------------------------

/*
 * Created on Apr 28, 2009
 */
package org.globus.cog.abstraction.coaster.service.job.manager;


import java.util.Collection;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.globus.cog.abstraction.impl.common.ProviderMethodException;
import org.globus.cog.abstraction.impl.common.execution.WallTime;
import org.globus.cog.abstraction.impl.common.task.ExecutionServiceImpl;
import org.globus.cog.abstraction.impl.common.task.InvalidProviderException;
import org.globus.cog.abstraction.impl.common.task.InvalidServiceContactException;
import org.globus.cog.abstraction.impl.common.task.JobSpecificationImpl;
import org.globus.cog.abstraction.impl.common.task.TaskImpl;
import org.globus.cog.abstraction.impl.execution.coaster.bootstrap.Bootstrap;
import org.globus.cog.abstraction.interfaces.ExecutionService;
import org.globus.cog.abstraction.interfaces.FileLocation;
import org.globus.cog.abstraction.interfaces.JobSpecification;
import org.globus.cog.abstraction.interfaces.Task;

public class BlockTask extends TaskImpl {
    public static final Logger logger = Logger.getLogger(BlockTask.class);

    private Block block;
    private Settings settings;

    public BlockTask(Block block) {
        this.block = block;
        this.settings = block.getAllocationProcessor().getSettings();
    }

    public void initialize() throws InvalidServiceContactException, InvalidProviderException,
            ProviderMethodException {
        setType(Task.JOB_SUBMISSION);
        JobSpecification spec = buildSpecification();
        setSpecification(spec);
        setName("Block-" + block.getId());
        setAttribute(spec, "maxwalltime", WallTime.format((int) block.getWalltime().getSeconds()));
        setAttribute(spec, "queue", settings.getQueue());
        setAttribute(spec, "project", settings.getProject());
        setAttribute(spec, "ppn", settings.getPpn());
        int count = block.getWorkerCount() / settings.getWorkersPerNode();
        if (count > 1) {
            setAttribute(spec, "jobType", "multiple");
        }
        setAttribute(spec, "count", String.valueOf(count));
        setAttribute(spec, "hostCount", String.valueOf(count));
        setAttribute(spec, "kernelprofile", settings.getKernelprofile());
        if (settings.getAlcfbgpnat()) {
            spec.addEnvironmentVariable("ZOID_ENABLE_NAT", "true");
        }
        spec.addEnvironmentVariable("WORKER_LOGGING_LEVEL", settings.getWorkerLoggingLevel());
        if (logger.isInfoEnabled()) {
            logger.info("Worker logging level: " + settings.getWorkerLoggingLevel());
        }
        setRequiredService(1);
        setService(0, buildService());
    }

    private JobSpecification buildSpecification() {
        JobSpecification js = new JobSpecificationImpl();
        js.setExecutable("/usr/bin/perl");
        // Cobalt on Intrepid, if no directory is specified, assumes $CWD for the
        // job directory.
        // If $CWD happens to be /scratch/something it has a filter in place
        // that rejects the job with the warning that /scratch/something is not accessible
        // on the worker node. And we don't care about the $CWD for the worker.
        js.setDirectory("/");

        js.addArgument(block.getAllocationProcessor().getScript().getAbsolutePath());
        js.addArgument(join(settings.getCallbackURIs(), ","));
        js.addArgument(block.getId());

        if (block.getWorkerCount() <= 16 ||
            "force".equals(System.getProperty("coaster.worker.logging"))) {
            js.addArgument(Bootstrap.LOG_DIR.getAbsolutePath());
        }
        else {
            js.addArgument("NOLOGGING");
        }

        logger.debug("arguments: " + js.getArguments());

        js.setStdOutputLocation(FileLocation.MEMORY);
        js.setStdErrorLocation(FileLocation.MEMORY);

        return js;
    }

    private String join(Collection<?> c, String sep) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> i = c.iterator();
        while (i.hasNext()) {
            sb.append(i.next().toString());
            if (i.hasNext()) {
                sb.append(sep);
            }
        }
        return sb.toString();
    }

    private ExecutionService buildService() throws InvalidServiceContactException,
            InvalidProviderException, ProviderMethodException {
        ExecutionService s = new ExecutionServiceImpl();
        s.setServiceContact(settings.getServiceContact());
        s.setProvider(settings.getProvider());
        s.setJobManager(settings.getJobManager());
        s.setSecurityContext(settings.getSecurityContext());
        return s;
    }

    public void setAttribute(JobSpecification spec, String name, Object value) {
        if (value != null) {
            spec.setAttribute(name, value);
        }
    }

    public void setAttribute(String name, int value) {
        super.setAttribute(name, String.valueOf(value));
    }
}
