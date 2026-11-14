package io.jenkins.plugins.bouteiller.pipeline;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.Run;
import io.jenkins.plugins.bouteiller.BouteillerConfiguration;
import io.jenkins.plugins.bouteiller.EventType;
import io.jenkins.plugins.bouteiller.push.BuildEventListener;
import io.jenkins.plugins.bouteiller.push.PushDispatcher;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;

/**
 * "Input pending": a new {@code input} step in the flow graph of any Pipeline build.
 *
 * <p>The node appears just before the step registers its execution on the build: the event is built once it is there,
 * off the thread of the Pipeline. A build resumed after a restart adds no node, so its pending inputs are not notified
 * again. Loaded only when Pipeline and the input step are installed ({@code optional}).
 */
@Extension(optional = true)
public final class InputPendingListener implements GraphListener {
    private static final Logger LOGGER = Logger.getLogger(InputPendingListener.class.getName());

    /** How long to wait for the execution of the step: it registers within milliseconds. */
    private static final long WAIT_MILLIS = 5_000;

    private static final long POLL_MILLIS = 100;

    @Override
    public void onNewHead(FlowNode node) {
        if (!StageGraph.isInputStep(node) || !BouteillerConfiguration.get().isPushEnabled()) {
            return;
        }
        Run<?, ?> run;
        try {
            Queue.Executable executable = node.getExecution().getOwner().getExecutable();
            if (!(executable instanceof Run<?, ?> owner)) {
                return;
            }
            run = owner;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "No build for " + node, e);
            return;
        }
        long occurredAt = System.currentTimeMillis();
        PushDispatcher.get().publish(run.getParent(), () -> {
            InputStepExecution execution = awaitExecution(run, node);
            if (execution == null || execution.isSettled()) {
                return null;
            }
            InputStep input = execution.getInput();
            return BuildEventListener.event(
                    run,
                    EventType.INPUT_PENDING,
                    null,
                    execution.getId(),
                    input.getMessage(),
                    !input.getParameters().isEmpty(),
                    occurredAt);
        });
    }

    private static InputStepExecution awaitExecution(Run<?, ?> run, FlowNode node) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        try {
            while (true) {
                InputStepExecution execution = PendingInputs.executionOf(run, node);
                if (execution != null || System.nanoTime() > deadline) {
                    return execution;
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
