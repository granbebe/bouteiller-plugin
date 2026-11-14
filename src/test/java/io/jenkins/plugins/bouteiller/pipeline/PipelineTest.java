package io.jenkins.plugins.bouteiller.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import io.jenkins.plugins.bouteiller.Registration;
import io.jenkins.plugins.bouteiller.RegistrationStore;
import io.jenkins.plugins.bouteiller.TestSupport;
import io.jenkins.plugins.bouteiller.push.FakeRelay;
import io.jenkins.plugins.bouteiller.push.PushDispatcher;
import io.jenkins.plugins.bouteiller.util.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Stages, failed stage, pending inputs and their notification, on real Pipeline builds. */
@WithJenkins
class PipelineTest {
    private JenkinsRule j;
    private FakeRelay relay;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.jenkins.setNumExecutors(2);
        // Users must exist in a security realm to check that they can read the job they are notified of.
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().toEveryone());
        relay = new FakeRelay();
        PushDispatcher.get().setRelayFactory(configuration -> relay);
    }

    private WorkflowJob pipeline(String name, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    private void register(String events) throws Exception {
        User.getById("alice", true);
        RegistrationStore.get().update("alice", "device", previous -> Registration.fromJson("device", "alice",
                Json.parseObject(TestSupport.registrationBody("ab".repeat(32), "[{\"events\":" + events + "}]")),
                previous, 0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) throws Exception {
        HttpResponse<String> response = TestSupport.call(j.getURL(), "GET", path, null, null, null);
        assertEquals(200, response.statusCode(), response.body());
        return Json.parseObject(response.body());
    }

    /** "name:status" of the stages, children in brackets. */
    @SuppressWarnings("unchecked")
    private static String outline(List<?> stages) {
        return stages.stream().map(value -> {
            Map<String, Object> stage = (Map<String, Object>) value;
            List<?> children = (List<?>) stage.get("children");
            return stage.get("name") + ":" + stage.get("status") + (children.isEmpty() ? "" : "[" + outline(children) + "]");
        }).collect(Collectors.joining(", "));
    }

    @Test
    void scriptedStagesWithParallelBranchesAndResults() throws Exception {
        WorkflowJob job = pipeline("scripted", """
                node {
                  stage('Build') { echo 'build' }
                  stage('Tests') {
                    parallel unit: { echo 'unit' }, ui: { unstable 'flaky' }
                  }
                  stage('Lint') { catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') { error 'style' } }
                  stage('Package') {
                    stage('Inner') { echo 'inner' }
                  }
                }
                stage('Outside') { echo 'no agent' }
                """);
        WorkflowRun run = j.buildAndAssertStatus(Result.UNSTABLE, job);
        Map<String, Object> body = get("bouteiller/api/v1/job/scripted/" + run.getNumber() + "/stages");
        assertEquals(Boolean.TRUE, body.get("complete"));
        List<?> stages = (List<?>) body.get("stages");
        assertEquals("Build:success, Tests:unstable[unit:success, ui:unstable], Lint:unstable, "
                + "Package:success[Inner:success], Outside:success", outline(stages));
        @SuppressWarnings("unchecked")
        Map<String, Object> build = (Map<String, Object>) stages.get(0);
        assertEquals("built-in", build.get("agent"));
        assertNotNull(build.get("startTime"));
        assertNotNull(build.get("duration"));
        @SuppressWarnings("unchecked")
        Map<String, Object> outside = (Map<String, Object>) stages.get(4);
        assertNull(outside.get("agent"));
    }

    @Test
    void declarativeSkippedAndParallelStages() throws Exception {
        WorkflowJob job = pipeline("declarative", """
                pipeline {
                  agent none
                  stages {
                    stage('Build') { steps { echo 'build' } }
                    stage('Deploy') {
                      when { expression { false } }
                      steps { echo 'never' }
                    }
                    stage('Checks') {
                      parallel {
                        stage('Unit') { steps { echo 'unit' } }
                        stage('Docs') { steps { error 'broken docs' } }
                      }
                    }
                    stage('After') { steps { echo 'after' } }
                  }
                }
                """);
        WorkflowRun run = j.buildAndAssertStatus(Result.FAILURE, job);
        List<?> stages = (List<?>) get("bouteiller/api/v1/job/declarative/" + run.getNumber() + "/stages").get("stages");
        assertEquals("Build:success, Deploy:skipped, Checks:failed[Unit:success, Docs:failed], After:skipped",
                outline(stages));
        assertEquals("Docs", PipelineRuns.failedStage(run));
    }

    @Test
    void failedEventNamesTheInnermostStage() throws Exception {
        register("[\"failed\"]");
        WorkflowJob job = pipeline("deploy", """
                stage('Release') {
                  stage('Deploy') { error 'connection refused' }
                }
                """);
        j.buildAndAssertStatus(Result.FAILURE, job);
        FakeRelay.Received failed = relay.await(1).get(0);
        assertEquals("failed", failed.eventType());
        assertEquals("Deploy", failed.event().get("failedStage"));

        WorkflowJob outside = pipeline("outside", "error 'before any stage'");
        j.buildAndAssertStatus(Result.FAILURE, outside);
        assertFalse(relay.await(2).get(1).event().containsKey("failedStage"));
    }

    @Test
    void notifiesPendingInputsAndListsThem() throws Exception {
        register("[\"inputPending\"]");
        MockFolder folder = j.createFolder("platform");
        WorkflowJob job = folder.createProject(WorkflowJob.class, "api");
        job.setDefinition(new CpsFlowDefinition("""
                stage('Approve') {
                  input id: 'Ship', message: 'Ship [it]?', ok: 'Ship'
                  input id: 'Target', message: 'Where?', parameters: [
                    choice(name: 'REGION', choices: ['eu', 'us']),
                    booleanParam(name: 'DRY_RUN', defaultValue: true),
                    password(name: 'SECRET', description: 'Token')
                  ]
                }
                """, true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        FakeRelay.Received first = relay.await(1).get(0);
        assertEquals("inputPending", first.eventType());
        assertEquals("Ship", first.event().get("inputId"));
        assertEquals("Ship [it]?", first.event().get("inputMessage"));
        assertEquals(Boolean.FALSE, first.event().get("inputHasParameters"));
        assertEquals("platform/api", first.event().get("jobFullName"));

        String base = "bouteiller/api/v1/job/platform/job/api/" + run.getNumber();
        assertEquals("Approve:paused", outline((List<?>) get(base + "/stages").get("stages")));
        assertEquals(Boolean.FALSE, get(base + "/stages").get("complete"));
        List<?> inputs = (List<?>) get(base + "/inputs").get("inputs");
        assertEquals(1, inputs.size());
        assertEquals(Map.of("id", "Ship", "message", "Ship [it]?", "ok", "Ship", "parameters", List.of()), inputs.get(0));

        run.getAction(InputAction.class).getExecution("Ship").proceed(null);
        FakeRelay.Received second = relay.await(2).get(1);
        assertEquals("Target", second.event().get("inputId"));
        assertEquals(Boolean.TRUE, second.event().get("inputHasParameters"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parameters =
                (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) get(base + "/inputs").get("inputs")).get(0))
                        .get("parameters");
        assertEquals(Map.of("name", "REGION", "type", "ChoiceParameterDefinition", "choices", List.of("eu", "us"),
                "defaultValue", "eu"), parameters.get(0));
        assertEquals(Boolean.TRUE, parameters.get(1).get("defaultValue"));
        assertFalse(parameters.get(2).containsKey("defaultValue"), "password defaults are never sent");

        run.getAction(InputAction.class).getExecution("Target").doAbort();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
        assertEquals("Approve:aborted", outline((List<?>) get(base + "/stages").get("stages")));
        Thread.sleep(300);
        assertEquals(2, relay.received().size());
    }

    @Test
    void runningStages() throws Exception {
        WorkflowJob job = pipeline("long", "stage('Wait') { sleep 60 }");
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Sleeping for", run);
        Map<String, Object> body = get("bouteiller/api/v1/job/long/" + run.getNumber() + "/stages");
        assertEquals("Wait:running", outline((List<?>) body.get("stages")));
        run.doStop();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
        assertEquals("Wait:aborted", outline((List<?>) get("bouteiller/api/v1/job/long/" + run.getNumber() + "/stages").get("stages")));
    }

    @Test
    void buildsOfOtherJobsAndHiddenJobsAreNotFound() throws Exception {
        WorkflowJob job = pipeline("secret", "stage('A') { echo 'a' }");
        j.buildAndAssertSuccess(job);
        j.createFreeStyleProject("freestyle").scheduleBuild2(0).get();
        assertEquals(404, TestSupport.call(j.getURL(), "GET", "bouteiller/api/v1/job/freestyle/1/stages", null, null, null).statusCode());
        assertEquals(404, TestSupport.call(j.getURL(), "GET", "bouteiller/api/v1/job/secret/2/stages", null, null, null).statusCode());
        assertEquals(404, TestSupport.call(j.getURL(), "GET", "bouteiller/api/v1/job/secret/1/log", null, null, null).statusCode());

        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("bob")
                .grant(Jenkins.READ, Item.READ).everywhere().to("alice"));
        String bob = TestSupport.apiToken("bob");
        String alice = TestSupport.apiToken("alice");
        assertEquals(404, TestSupport.call(j.getURL(), "GET", "bouteiller/api/v1/job/secret/1/stages", "bob", bob, null).statusCode());
        assertEquals(200, TestSupport.call(j.getURL(), "GET", "bouteiller/api/v1/job/secret/1/stages", "alice", alice, null).statusCode());
    }
}
