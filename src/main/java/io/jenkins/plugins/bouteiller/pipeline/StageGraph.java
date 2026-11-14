package io.jenkins.plugins.bouteiller.pipeline;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Result;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TagsAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

/**
 * Stages of a Pipeline build computed from its flow graph, without Stage View, Graph View or
 * {@code pipeline-graph-analysis}: the format stays the same whatever display plugins are installed.
 *
 * <p>A stage is the body of a {@code stage} step; a parallel branch is the body of a branch of {@code parallel}. Both
 * nest: the children of a stage are its nested stages and branches. A branch that only wraps a stage of the same name
 * (parallel stages of Declarative) is replaced by that stage.
 */
public final class StageGraph {
    /** Statuses in the names of the app ({@code StageStatus}). */
    public enum Status {
        SUCCESS("success"),
        FAILED("failed"),
        UNSTABLE("unstable"),
        ABORTED("aborted"),
        SKIPPED("skipped"),
        RUNNING("running"),
        /** Waiting for an input. */
        PAUSED("paused"),
        NOT_BUILT("notBuilt");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /**
     * A stage or a parallel branch.
     *
     * @param startTime  milliseconds since 1970, {@code null} when unknown
     * @param duration   milliseconds; up to now for a running stage
     * @param agent      the node it ran on ({@code built-in} for the controller), {@code null} outside of any node
     */
    public record Stage(
            String id,
            String name,
            Status status,
            @CheckForNull Long startTime,
            @CheckForNull Long duration,
            @CheckForNull String agent,
            List<Stage> children) {

        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("name", name);
            json.put("status", status.wireName());
            if (startTime != null) {
                json.put("startTime", startTime);
            }
            if (duration != null) {
                json.put("duration", duration);
            }
            if (agent != null) {
                json.put("agent", agent);
            }
            json.put("children", children.stream().map(Stage::toJson).toList());
            return json;
        }
    }

    /** Tag of Declarative on the stages it did not run ({@code SKIPPED_FOR_CONDITIONAL}, {@code SKIPPED_FOR_FAILURE}…). */
    static final String STAGE_STATUS_TAG = "STAGE_STATUS";

    private final FlowExecution execution;
    private final long now;

    /** Every node of the graph, with the start of each block mapped to its end. */
    private final List<FlowNode> nodes;

    private final Map<String, BlockEndNode<?>> ends = new HashMap<>();
    /** The stages and branches, by node id. */
    private final Map<String, BlockStartNode> sections = new LinkedHashMap<>();
    /** Worst warning (unstable step, {@code catchError}) inside each section. */
    private final Map<String, Result> warnings = new HashMap<>();
    /** First agent used inside each section. */
    private final Map<String, String> innerAgents = new HashMap<>();
    /** Sections with a pending {@code input} among the current heads. */
    private final Set<String> paused;

    private StageGraph(FlowExecution execution, long now) {
        this.execution = execution;
        this.now = now;
        this.nodes = new DepthFirstScanner().allNodes(execution);
        for (FlowNode node : nodes) {
            if (node instanceof BlockEndNode<?> end) {
                ends.put(end.getStartNode().getId(), end);
            }
            if (node instanceof BlockStartNode start && (isStage(start) || isBranch(start))) {
                sections.put(start.getId(), start);
            }
        }
        for (FlowNode node : nodes) {
            WarningAction warning = node.getPersistentAction(WarningAction.class);
            WorkspaceAction workspace = node.getPersistentAction(WorkspaceAction.class);
            if (warning == null && workspace == null) {
                continue;
            }
            for (BlockStartNode block : node.iterateEnclosingBlocks()) {
                if (!sections.containsKey(block.getId())) {
                    continue;
                }
                if (warning != null) {
                    warnings.merge(block.getId(), warning.getResult(), (a, b) -> a.isWorseThan(b) ? a : b);
                }
                if (workspace != null) {
                    innerAgents.putIfAbsent(block.getId(), agentName(workspace));
                }
            }
        }
        this.paused = execution.getCurrentHeads().stream()
                .filter(StageGraph::isInputStep)
                .flatMap(head -> head.getAllEnclosingIds().stream())
                .collect(Collectors.toSet());
    }

    /** The stages of a build, in the order they started. */
    public static List<Stage> stages(FlowExecution execution) {
        return new StageGraph(execution, System.currentTimeMillis()).tree();
    }

    /**
     * Name of the innermost stage where the build failed, {@code null} when the failure happened outside of any stage
     * or cannot be located.
     */
    @CheckForNull
    public static String failedStage(FlowExecution execution) {
        Throwable cause = execution.getCauseOfFailure();
        FlowNode origin = cause == null ? null : ErrorAction.findOrigin(cause, execution);
        if (origin == null) {
            return null;
        }
        if (origin instanceof BlockStartNode start && isStage(start)) {
            return label(start);
        }
        for (BlockStartNode block : origin.iterateEnclosingBlocks()) {
            if (isStage(block)) {
                return label(block);
            }
        }
        return null;
    }

    private List<Stage> tree() {
        Map<String, List<BlockStartNode>> childrenOf = new HashMap<>();
        List<BlockStartNode> roots = new ArrayList<>();
        for (BlockStartNode section : sections.values()) {
            BlockStartNode parent = parentSection(section);
            if (parent == null) {
                roots.add(section);
            } else {
                childrenOf.computeIfAbsent(parent.getId(), id -> new ArrayList<>()).add(section);
            }
        }
        return build(roots, childrenOf, null);
    }

    private List<Stage> build(
            List<BlockStartNode> sectionList, Map<String, List<BlockStartNode>> childrenOf, String outerAgent) {
        return sectionList.stream()
                .sorted(Comparator.comparingInt(StageGraph::order))
                .map(section -> {
                    String agent = enclosingAgent(section);
                    if (agent == null) {
                        agent = innerAgents.get(section.getId());
                    }
                    if (agent == null) {
                        agent = outerAgent;
                    }
                    List<Stage> children =
                            build(childrenOf.getOrDefault(section.getId(), List.of()), childrenOf, agent);
                    Stage stage = stage(section, agent, children);
                    // A branch wrapping a stage of the same name (Declarative parallel stages): the stage alone.
                    if (isBranch(section)
                            && children.size() == 1
                            && children.get(0).name().equals(stage.name())) {
                        return children.get(0);
                    }
                    return stage;
                })
                .toList();
    }

    private Stage stage(BlockStartNode start, String agent, List<Stage> children) {
        long startTime = TimingAction.getStartTime(start);
        BlockEndNode<?> end = ends.get(start.getId());
        Long duration = null;
        if (startTime > 0) {
            if (end != null && TimingAction.getStartTime(end) > 0) {
                duration = TimingAction.getStartTime(end) - startTime;
            } else if (end == null && !execution.isComplete()) {
                duration = Math.max(0, now - startTime);
            }
        }
        return new Stage(
                start.getId(),
                isBranch(start) ? branchName(start) : label(start),
                status(start, end),
                startTime > 0 ? startTime : null,
                duration,
                agent,
                children);
    }

    private Status status(BlockStartNode start, @CheckForNull BlockEndNode<?> end) {
        String tag = tag(start);
        if (tag != null && tag.startsWith("SKIPPED")) {
            return Status.SKIPPED;
        }
        if (end == null) {
            if (execution.isComplete()) {
                // The build ended without closing the block: killed.
                return Status.ABORTED;
            }
            return paused.contains(start.getId()) ? Status.PAUSED : Status.RUNNING;
        }
        ErrorAction error = end.getPersistentAction(ErrorAction.class);
        if (error != null) {
            return errorStatus(error.getError());
        }
        Result warning = warnings.get(start.getId());
        if (warning != null) {
            return resultStatus(warning);
        }
        return Status.SUCCESS;
    }

    private static Status errorStatus(Throwable error) {
        if (error instanceof FlowInterruptedException interrupted) {
            return resultStatus(interrupted.getResult());
        }
        return Status.FAILED;
    }

    private static Status resultStatus(Result result) {
        if (result == Result.SUCCESS) {
            return Status.SUCCESS;
        }
        if (result == Result.UNSTABLE) {
            return Status.UNSTABLE;
        }
        if (result == Result.ABORTED) {
            return Status.ABORTED;
        }
        if (result == Result.NOT_BUILT) {
            return Status.NOT_BUILT;
        }
        return Status.FAILED;
    }

    /** The Declarative status tag, on the body of the stage or on the step that opened it. */
    @CheckForNull
    private static String tag(BlockStartNode start) {
        String tag = TagsAction.getTagValue(start, STAGE_STATUS_TAG);
        if (tag == null) {
            for (FlowNode parent : start.getParents()) {
                tag = TagsAction.getTagValue(parent, STAGE_STATUS_TAG);
                if (tag != null) {
                    break;
                }
            }
        }
        return tag;
    }

    @CheckForNull
    private BlockStartNode parentSection(BlockStartNode section) {
        for (BlockStartNode block : section.iterateEnclosingBlocks()) {
            if (sections.containsKey(block.getId())) {
                return block;
            }
        }
        return null;
    }

    /** The agent of the nearest enclosing {@code node} block. */
    @CheckForNull
    private static String enclosingAgent(BlockStartNode section) {
        for (BlockStartNode block : section.iterateEnclosingBlocks()) {
            WorkspaceAction workspace = block.getPersistentAction(WorkspaceAction.class);
            if (workspace != null) {
                return agentName(workspace);
            }
        }
        return null;
    }

    private static String agentName(WorkspaceAction workspace) {
        String node = workspace.getNode();
        return node == null || node.isEmpty() ? "built-in" : node;
    }

    /** The body of a {@code stage} step: the start node of the step itself carries no label. */
    static boolean isStage(BlockStartNode start) {
        return isStep(start, "stage")
                && start.getPersistentAction(LabelAction.class) != null
                && start.getPersistentAction(ThreadNameAction.class) == null;
    }

    /** The body of a branch of {@code parallel}. */
    static boolean isBranch(BlockStartNode start) {
        return start.getPersistentAction(ThreadNameAction.class) != null;
    }

    static boolean isInputStep(FlowNode node) {
        return isStep(node, "input");
    }

    private static boolean isStep(FlowNode node, String functionName) {
        if (!(node instanceof StepNode step)) {
            return false;
        }
        StepDescriptor descriptor = step.getDescriptor();
        return descriptor != null && functionName.equals(descriptor.getFunctionName());
    }

    private static String label(BlockStartNode start) {
        LabelAction label = start.getPersistentAction(LabelAction.class);
        return label == null ? start.getDisplayName() : label.getDisplayName();
    }

    private static String branchName(BlockStartNode start) {
        ThreadNameAction thread = start.getPersistentAction(ThreadNameAction.class);
        return thread == null ? label(start) : thread.getThreadName();
    }

    /** Node ids are increasing integers: their order is the order of creation. */
    private static int order(FlowNode node) {
        try {
            return Integer.parseInt(node.getId());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
