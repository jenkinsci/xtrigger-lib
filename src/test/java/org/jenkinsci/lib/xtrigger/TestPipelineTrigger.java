/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.lib.xtrigger;

import antlr.ANTLRException;
import hudson.FilePath;
import hudson.model.Action;
import hudson.model.Node;

import java.io.File;
import java.io.IOException;

/**
 * Explicit trigger for testing purposes.
 *
 * @author ogondza
 */
public class TestPipelineTrigger extends AbstractPipelineTrigger {
    private static final long serialVersionUID = 1L;

    private final File log;
    private volatile boolean triggered = false;

    public TestPipelineTrigger() throws ANTLRException {
        super("* * * * *");
        try {
            log = File.createTempFile("xtrigger", "test");
            log.deleteOnExit();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    public void trigger() {
        triggered = true;
    }

    @Override
    protected File getLogFile() {
        return log;
    }

    @Override
    protected Action[] getScheduledActions(Node node, XTriggerLog log) {
        return new Action[] {};
    }

    @Override
    protected boolean requiresWorkspaceForPolling() {
        return false;
    }

    @Override
    protected String getName() {
        return getClass().getCanonicalName();
    }

    @Override
    protected boolean checkIfModified(Node pollingNode, XTriggerLog log) throws XTriggerException {
        return triggered;
    }

    @Override
    protected String getCause() {
        return "Triggered by test";
    }

    public String getLog() throws IOException, InterruptedException {
        return new FilePath(getLogFile()).readToString();
    }

    @Override
    public Descriptor getDescriptor() {
        return DESCRIPTOR;
    }

    private static final Descriptor DESCRIPTOR = new Descriptor();
    public static final class Descriptor extends XTriggerDescriptor {
        @Override
        public String getDisplayName() {
            return "Explicit trigger for tests";
        }
    }
}