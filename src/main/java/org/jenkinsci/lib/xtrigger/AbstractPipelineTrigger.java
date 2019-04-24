package org.jenkinsci.lib.xtrigger;

import antlr.ANTLRException;
import hudson.FilePath;
import hudson.Util;
import hudson.model.*;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.SubTask;
import hudson.triggers.Trigger;
import hudson.util.NullStream;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.service.EnvVarsResolver;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Gregory Boissinot
 */
public abstract class AbstractPipelineTrigger extends Trigger<Job<?,?>> implements Serializable {

    protected static Logger LOGGER = Logger.getLogger(AbstractPipelineTrigger.class.getName());

    private String triggerLabel;

    private transient boolean unblockConcurrentBuild;
    protected transient boolean offlineSlaveOnStartup = false;

    public AbstractPipelineTrigger(String cronTabSpec) throws ANTLRException {
        super(cronTabSpec);
        this.unblockConcurrentBuild = false;
    }

    protected AbstractPipelineTrigger(String cronTabSpec, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec);
        this.unblockConcurrentBuild = unblockConcurrentBuild;
    }

    protected AbstractPipelineTrigger(String cronTabSpec, String triggerLabel) throws ANTLRException {
        super(cronTabSpec);
        this.triggerLabel = Util.fixEmpty(triggerLabel);
        this.unblockConcurrentBuild = false;
    }

    protected AbstractPipelineTrigger(String cronTabSpec, String triggerLabel, boolean unblockConcurrentBuild) throws ANTLRException {
        super(cronTabSpec);
        this.triggerLabel = Util.fixEmpty(triggerLabel);
        this.unblockConcurrentBuild = unblockConcurrentBuild;
    }

    @SuppressWarnings("unused")
    public String getTriggerLabel() {
        return triggerLabel;
    }

    /**
     * Gets the triggering log file
     *
     * @return the trigger log
     */
    protected abstract File getLogFile();

    /**
     * Checks if a consistency workspace is required for the polling
     *
     * @return true if a workspace is required for the job, false otherwise
     */
    protected abstract boolean requiresWorkspaceForPolling();

    @Override
    public void start(Job<?, ?>  project, boolean newInstance) {
        super.start(project, newInstance);

        XTriggerLog log = new XTriggerLog(new StreamTaskListener(new NullStream()));
        Node pollingNode = getPollingNode(log);
        if (pollingNode == null) {
            log.info("Can't find any complete active node.");
            log.info("Checking again in next polling schedule.");
            log.info("Waiting for next schedule.");
            offlineSlaveOnStartup = true;
            return;
        }

        if (pollingNode.getRootPath() == null) {
            log.info("The running slave might be offline at the moment.");
            log.info("Waiting for next schedule.");
            offlineSlaveOnStartup = true;
            return;
        }

        try {
            start(pollingNode, project, newInstance, log);
        } catch (XTriggerException xe) {
            LOGGER.log(Level.SEVERE, "Can't initialize trigger", xe);
        }
    }

    /**
     * Can be overridden if needed
     */
    protected void start(Node pollingNode, Job<?, ?>  project, boolean newInstance, XTriggerLog log) throws XTriggerException {
    }

    @Deprecated // as of 0.34.
    protected String resolveEnvVars(String value, AbstractProject project, Node node) throws XTriggerException {
        EnvVarsResolver varsResolver = new EnvVarsResolver();
        Map<String, String> envVars;
        try {
            envVars = varsResolver.getPollingEnvVars(project, node);
        } catch (EnvInjectException envInjectException) {
            throw new XTriggerException(envInjectException);
        }
        return Util.replaceMacro(value, envVars);
    }

    @Override
    public void run() {
        LOGGER.fine(String.format("running trigger: %s : %s : %s ", this.getName(), this.getTriggerLabel(), this));
        Job project = (Job) job;
        XTriggerDescriptor descriptor = getDescriptor();
        ExecutorService executorService = descriptor.getExecutor();
        XTriggerLog log = null;
        try {
            StreamTaskListener listener = new StreamTaskListener(getLogFile());
            log = new XTriggerLog(listener);
            if (Hudson.getInstance().isQuietingDown()) {
                log.info("Jenkins is quieting down.");
            } else if (!project.isBuildable()) {
                log.info("The job is not buildable. Activate it to poll again.");
            } else if (!unblockConcurrentBuild && project.isBuilding()) {
                log.info("The job is building. Waiting for next poll.");
            } else {
                LOGGER.fine("get runner");
                Runner runner = new Runner(getName());
                LOGGER.fine(String.format("with runner: %s", runner));
                executorService.execute(runner);
                LOGGER.fine("passed to executor service");
            }
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Severe error during the trigger execution " + t.getMessage());
            t.printStackTrace();
        } finally {
            if (log != null) {
                log.closeQuietly();
            }
        }
    }

    protected abstract String getName();

    public XTriggerDescriptor getDescriptor() {
        return (XTriggerDescriptor) Hudson.getInstance().getDescriptorOrDie(getClass());
    }

    /**
     * Asynchronous task
     */
    private class Runner implements Runnable, Serializable {

        private String triggerName;

        public Runner(String triggerName) {
            this.triggerName = triggerName;
        }

        @Override
        public void run() {
            XTriggerLog log = null;
            try {
                LOGGER.fine("running runner");

                StreamTaskListener listener = new StreamTaskListener(getLogFile());
                log = new XTriggerLog(listener);

                long start = System.currentTimeMillis();
                log.info("Polling started on " + DateFormat.getDateTimeInstance().format(new Date(start)));
                log.info("Polling for the job " + job.getName());

                LOGGER.fine("Polling started on " + DateFormat.getDateTimeInstance().format(new Date(start)));
                LOGGER.fine("Polling for the job " + job.getName());

                boolean changed;

                if (requirePollingNode()) {

                    Node pollingNode = getPollingNode(log);
                    if (pollingNode == null) {
                        log.info("Can't find any complete active node for the polling action.");
                        log.info("Maybe slaves are not yet active at this time or the number of executor of the master is 0.");
                        log.info("Checking again in next polling schedule.");
                        LOGGER.fine("Can't find any complete active node for the polling action.");
                        LOGGER.fine("Maybe slaves are not yet active at this time or the number of executor of the master is 0.");
                        LOGGER.fine("Checking again in next polling schedule.");
                        return;
                    }

                    if (pollingNode.getRootPath() == null) {
                        log.info("The running slave might be offline at the moment.");
                        log.info("Waiting for next schedule.");
                        LOGGER.fine("The running slave might be offline at the moment.");
                        LOGGER.fine("Waiting for next schedule.");
                        return;
                    }

                    displayPollingNode(pollingNode, log);
                    changed = checkIfModified(pollingNode, log);

                } else {
                    changed = checkIfModified(log);
                }


                log.info("\nPolling complete. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start) + ".");
                LOGGER.fine("Polling complete. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start) + ".");

                if (changed) {
                    log.info("Changes found. Scheduling a build.");
                    LOGGER.fine("Changes found. Scheduling a build.");

                    //TODO: Check whether the schedule() operation returns non-null future
                    List<Action> actions = new ArrayList<Action>(Arrays.asList(getScheduledXTriggerActions(null, log)));
                    actions.add(new CauseAction(new XTriggerCause(triggerName, getCause(), true)));

                    ParameterizedJobMixIn<?, ?> jobMixIn = new ParameterizedJobMixIn() {
                        @Override
                        protected Job<?, ?> asJob() {
                            return (Job<?, ?>)job;
                        }
                    };
                    QueueTaskFuture<?> futureTask = jobMixIn.scheduleBuild2(0,actions.toArray(new Action[0]));
                    LOGGER.fine("Scheduled build: " + futureTask);
                    //hudson.model.Queue.getInstance().schedule(job, 0, actions);
                } else {
                    log.info("No changes.");
                    LOGGER.fine("No changes.");
                }
            } catch (XTriggerException e) {
                reportError(log, e);
            } catch (Throwable e) {
                reportError(log, e);
            } finally {
                if (log != null) {
                    log.closeQuietly();
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Runner runner = (Runner) o;

            if (triggerName != null ? !triggerName.equals(runner.triggerName) : runner.triggerName != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return triggerName != null ? triggerName.hashCode() : 0;
        }
    }

    private void reportError(XTriggerLog log, Throwable e) {
        log.error("Polling error...");
        String message = e.getMessage();
        if (message != null) {
            log.error("Error message: " + message);
        }
        Throwable cause = e.getCause();
        if (cause != null) {
            log.error("Error cause: " + cause.getMessage());
        }
        LOGGER.log(Level.WARNING, "Polling failed", e);
    }

    protected Action[] getScheduledXTriggerActions(Node pollingNode, XTriggerLog log) throws XTriggerException {
        Action[] actions = getScheduledActions(pollingNode, log);
        int nbNewAction = actions.length + 1;
        Action[] newActions = new Action[nbNewAction];
        for (int i = 0; i < actions.length; i++) {
            newActions[i] = actions[i];
        }
        try {
            newActions[newActions.length - 1] = new XTriggerCauseAction(FileUtils.readFileToString(getLogFile()));
        } catch (IOException ioe) {
            throw new XTriggerException(ioe);
        }
        return newActions;
    }

    protected abstract Action[] getScheduledActions(Node pollingNode, XTriggerLog log);

    /**
     * Checks if there are modifications in the environment between last poll
     *
     * @return true if there are modifications
     */
    protected abstract boolean checkIfModified(Node pollingNode, XTriggerLog log) throws XTriggerException;

    protected boolean requirePollingNode() {
        return true;
    }

    protected boolean checkIfModified(XTriggerLog log) throws XTriggerException {
        return true;
    }

    /**
     * Gets the trigger cause
     *
     * @return the trigger cause
     */
    protected abstract String getCause();

    /**
     * Get the node where the polling need to be done
     * <p/>
     * The returned node has a number of executor different of 0.
     *
     * @param log
     * @return the node; null if there is no available node
     */
    private Node getPollingNode(XTriggerLog log) {
        List<Node> nodes = getPollingNodesWithExecutors(log);
        if (nodes == null || nodes.size() == 0) {
            return null;
        }
        //Get the first eligible node
        return nodes.get(0);
    }

    private void displayPollingNode(Node node, XTriggerLog log) {
        assert node != null;
        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.trim().length() == 0) {
            log.info("\nPolling on master.");
        } else {
            log.info("\nPolling remotely on " + nodeName);
        }
    }

    private List<Node> getPollingNodesWithExecutors(XTriggerLog log) {
        List<Node> result = new ArrayList<Node>();
        List<Node> nodes = getPollingNodeList(log);
        for (Node node : nodes) {
            if (node != null && eligibleNode(node)) {
                result.add(node);
            } else if (node != null) {
                log.info(String.format("Finding %s but it is not eligible.", node.getDisplayName()));
            }
        }
        return result;
    }

    private List<Node> getPollingNodeList(XTriggerLog log) {
        log.info("Looking nodes where the poll can be run.");

        List<Node> nodes;
        if (requiresWorkspaceForPolling()) {
            nodes = getPollingNodeListRequiredWS(log);
        } else {
            nodes = getPollingNodeListRequiredNoWS(log);
        }

        if (nodes == null || nodes.size() == 0) {
            log.info("Can't find any eligible slave nodes.");
            log.info("Trying to poll on master node.");
            nodes = Arrays.asList(getMasterNode());
        }

        return nodes;
    }

    private List<Node> getPollingNodeListRequiredNoWS(XTriggerLog log) {

        //The specified trigger node must be considered first
        if (triggerLabel != null) {
            log.info(String.format("Looking for a node to the restricted label %s.", triggerLabel));

            if ("master".equalsIgnoreCase(triggerLabel)) {
                log.info("Restrict on master label. Polling on master.");
                return Arrays.asList(getMasterNode());
            }

            Label targetLabel = Hudson.getInstance().getLabel(triggerLabel);
            return getNodesLabel(job, targetLabel);
        }

        return candidatePollingNode(log);
    }

    private List<Node> getPollingNodeListRequiredWS(XTriggerLog log) {

        //The specified trigger node must be considered first
        if (triggerLabel != null) {

            log.info(String.format("Looking for a polling node to the restricted label %s.", triggerLabel));

            if ("master".equalsIgnoreCase(triggerLabel)) {
                log.info("Polling on master.");
                return Arrays.asList(getMasterNode());
            }

            Label targetLabel = Hudson.getInstance().getLabel(triggerLabel);
            return getNodesLabel(job, targetLabel);
        }

        //Search for the last built on
        log.info("Looking for the last built on node.");
        if (job instanceof SubTask) {
            Node lastBuildOnNode = ((SubTask)job).getLastBuiltOn();
            if (lastBuildOnNode != null) {
                return Arrays.asList(lastBuildOnNode);
            }
        }
        return getPollingNodeNoPreviousBuild(log);
    }

    private boolean eligibleNode(Node node) {
        if (node == null) {
            return false;
        }

        if (node.getRootPath() == null) {
            return false;
        }

        return node.getNumExecutors() != 0;
    }

    private List<Node> getPollingNodeNoPreviousBuild(XTriggerLog log) {
        Label targetLabel = getTargetLabel(log);
        if (targetLabel != null) {
            return getNodesLabel(job, targetLabel);
        }
        return null;
    }

    private List<Node> candidatePollingNode(XTriggerLog log) {
        log.info("Looking for a candidate node to run the poll.");

        Label targetLabel = getTargetLabel(log);
        if (targetLabel != null) {
            return getNodesLabel(job, targetLabel);
        } else {
            return Jenkins.getInstance().getNodes();
        }
    }

    /**
     * Returns the label if any to poll
     */
    private Label getTargetLabel(XTriggerLog log) {
        if (job instanceof SubTask) {
            Label assignedLabel = ((SubTask) job).getAssignedLabel();
            if (assignedLabel != null) {
                log.info(String.format("Trying to find an eligible node with the assigned project label %s.", assignedLabel));
                return assignedLabel;
            }
        }
        return null;
    }

    private Node getMasterNode() {
        Computer computer = Hudson.getInstance().toComputer();
        if (computer != null) {
            return computer.getNode();
        } else {
            return null;
        }
    }

    private List<Node> getNodesLabel(Job<?,?> buildable, Label label) {
        List<Node> result = new ArrayList<Node>();
        List<Node> remainingNodes = new ArrayList<Node>();

        Set<Node> nodes = label.getNodes();
        for (Node node : nodes) {
            if (node != null) {
                if (!isAPreviousBuildNode(buildable)) {
                    FilePath nodePath = node.getRootPath();
                    if (nodePath != null) {
                        result.add(node);
                    }
                } else {
                    FilePath nodeRootPath = node.getRootPath();
                    if (nodeRootPath != null) {
                        //We recommend first the samed node
                        if (buildable instanceof SubTask) {
                            Node lastBuildOnNode = ((SubTask) buildable).getLastBuiltOn();
                            if (lastBuildOnNode != null && nodeRootPath.equals(lastBuildOnNode.getRootPath())) {
                                result.add(0, node);
                            }
                        } else {
                            remainingNodes.add(node);
                        }
                    }
                }
            }
        }

        if (result.size() > 0) {
            return result;
        } else {
            return remainingNodes;
        }
    }

    private boolean isAPreviousBuildNode(Job<?,?> buildable) {
        if (buildable instanceof SubTask) {
            Node lastBuildOnNode = ((SubTask) buildable).getLastBuiltOn();
            return lastBuildOnNode != null;
        }
        return false;
    }
}
