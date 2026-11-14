package io.jenkins.plugins.bouteiller.pipeline;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.BooleanParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.PasswordParameterDefinition;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;

/** The inputs a Pipeline build is waiting for, from the {@code input} step. */
public final class PendingInputs {
    private PendingInputs() {}

    /**
     * The pending inputs of a build, as {@code GET /job/.../{n}/inputs} returns them: identifier (used in the URLs of
     * Jenkins, {@code input/{id}/proceed}), message, labels of the buttons, submitters and parameters.
     */
    public static List<Map<String, Object>> toJson(Run<?, ?> run) throws InterruptedException {
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (InputStepExecution execution : executions(run)) {
            if (execution.isSettled()) {
                continue;
            }
            InputStep input = execution.getInput();
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", execution.getId());
            json.put("message", input.getMessage());
            putIfPresent(json, "ok", input.getOk());
            putIfPresent(json, "submitter", input.getSubmitter());
            json.put("parameters", input.getParameters().stream().map(PendingInputs::parameter).toList());
            inputs.add(json);
        }
        return inputs;
    }

    /** The execution of the {@code input} step of this node, {@code null} while it is not registered yet. */
    @CheckForNull
    public static InputStepExecution executionOf(Run<?, ?> run, FlowNode node) throws InterruptedException {
        for (InputStepExecution execution : executions(run)) {
            try {
                FlowNode executionNode = execution.getContext().get(FlowNode.class);
                if (executionNode != null && executionNode.getId().equals(node.getId())) {
                    return execution;
                }
            } catch (IOException e) {
                // The execution lost its context (build ending): not this one.
            }
        }
        return null;
    }

    private static List<InputStepExecution> executions(Run<?, ?> run) throws InterruptedException {
        InputAction action = run.getAction(InputAction.class);
        if (action == null) {
            return List.of();
        }
        try {
            return action.getExecutions();
        } catch (TimeoutException e) {
            // The program of the build is still loading (after a restart).
            return List.of();
        }
    }

    /** A parameter of an input; password defaults are never sent. */
    static Map<String, Object> parameter(ParameterDefinition definition) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", definition.getName());
        json.put("type", definition.getType());
        putIfPresent(json, "description", definition.getDescription());
        if (definition instanceof ChoiceParameterDefinition choice) {
            json.put("choices", List.copyOf(choice.getChoices()));
        }
        if (!(definition instanceof PasswordParameterDefinition)) {
            ParameterValue value = definition.getDefaultParameterValue();
            if (value instanceof BooleanParameterValue bool) {
                json.put("defaultValue", bool.getValue());
            } else if (value instanceof StringParameterValue string) {
                json.put("defaultValue", string.getValue());
            } else if (definition instanceof BooleanParameterDefinition bool) {
                json.put("defaultValue", bool.isDefaultValue());
            }
        }
        return json;
    }

    private static void putIfPresent(Map<String, Object> json, String key, @CheckForNull String value) {
        if (value != null && !value.isEmpty()) {
            json.put(key, value);
        }
    }
}
