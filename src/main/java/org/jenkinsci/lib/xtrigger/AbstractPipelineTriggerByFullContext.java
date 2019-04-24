package org.jenkinsci.lib.xtrigger;

import antlr.ANTLRException;
import hudson.model.Job;
import hudson.model.Node;


/**
 * @author Gregory Boissinot
 */
public abstract class AbstractPipelineTriggerByFullContext<C extends XTriggerContext> extends AbstractPipelineTrigger {

    private transient C context;

    private transient Object lock = new Object();

    /**
     * Builds a trigger object
     * Calls an implementation trigger
     *
     * @param cronTabSpec the scheduler value
     * @throws ANTLRException the expression language expression
     */
    public AbstractPipelineTriggerByFullContext(String cronTabSpec) throws ANTLRException {
        super(cronTabSpec);
    }

    protected AbstractPipelineTriggerByFullContext(String cronTabSpec, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec, unblockConcurrentBuild);
    }

    protected AbstractPipelineTriggerByFullContext(String cronTabSpec, String triggerLabel) throws ANTLRException {
        super(cronTabSpec, triggerLabel);
    }

    protected AbstractPipelineTriggerByFullContext(String cronTabSpec, String triggerLabel, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec, triggerLabel, unblockConcurrentBuild);
    }

    /**
     * Can be overridden if needed
     */
    @Override
    protected void start(Node pollingNode, Job<?,?> project, boolean newInstance, XTriggerLog log) throws XTriggerException {
        if (isContextOnStartupFetched()) {
            context = getContext(pollingNode, log);
        }
    }

    public abstract boolean isContextOnStartupFetched();

    @Override
    protected boolean checkIfModified(Node pollingNode, XTriggerLog log) throws XTriggerException {

        // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {

            C newContext = getContext(pollingNode, log);

            if (offlineSlaveOnStartup) {
                log.info("No nodes were available at startup or at previous poll.");
                log.info("Recording environment context and waiting for next schedule to check if there are modifications.");
                offlineSlaveOnStartup = false;
                setNewContext(newContext);
                return false;
            }

            if (context == null) {
                log.info("Recording context. Check changes in next poll.");
                setNewContext(newContext);
                return false;
            }

            boolean changed = checkIfModified(context, newContext, log);
            return changed;
        }
    }

    @Override
    protected boolean checkIfModified(XTriggerLog log) throws XTriggerException {
        
        // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {
            C newContext = getContext(log);

            if (context == null) {
                log.info("Recording context. Check changes in next poll.");
                setNewContext(newContext);
                return false;
            }

            boolean changed = checkIfModified(context, newContext, log);
            return changed;
        }
    }

    protected void setNewContext(C context) {
        
         // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {
            this.context = context;
        }
    }

    /**
     * Resets the current context to the old context
     *
     * @param oldContext the previous context
     */
    protected void resetOldContext(C oldContext) {
        
         // make sure the lock is not null; when de-serialising
        if(lock==null){
            lock = new Object();
        }
        
        synchronized (lock) {
            this.context = oldContext;
        }
    }

    /**
     * Captures the context
     * This method is alternative to getContext(XTriggerLog log)
     * It must be overridden
     * from 0.26
     */
    protected C getContext(Node pollingNode, XTriggerLog log) throws XTriggerException {
        return null;
    }

    /**
     * Captures the context
     * This method is alternative to getContext(Node pollingNode, XTriggerLog log)
     * It must be overridden
     * from 0.26
     */
    protected C getContext(XTriggerLog log) throws XTriggerException {
        return null;
    }

    /**
     * Checks if there are modifications in the environment between last poll
     *
     * @return true if there are modifications
     */
    protected abstract boolean checkIfModified(C oldContext, C newContext, XTriggerLog log) throws XTriggerException;

}
