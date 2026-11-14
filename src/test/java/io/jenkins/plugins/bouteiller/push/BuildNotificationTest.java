package io.jenkins.plugins.bouteiller.push;

import static io.jenkins.plugins.bouteiller.TestSupport.SERVER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import io.jenkins.plugins.bouteiller.BouteillerConfiguration;
import io.jenkins.plugins.bouteiller.EventType;
import io.jenkins.plugins.bouteiller.Registration;
import io.jenkins.plugins.bouteiller.RegistrationStore;
import io.jenkins.plugins.bouteiller.TestSupport;
import io.jenkins.plugins.bouteiller.util.Json;
import java.time.Duration;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildNotificationTest {
    private JenkinsRule j;
    private FakeRelay relay;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        relay = new FakeRelay();
        PushDispatcher.get().setRelayFactory(configuration -> relay);
        PushDispatcher.get().setRetryDelays(List.of(Duration.ofMillis(10), Duration.ofMillis(10)));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    }

    private void register(String userId, String deviceToken, String subscriptionsJson) throws Exception {
        // The user exists, as when the app registered with the user's API token.
        User.getById(userId, true);
        RegistrationStore.get().update(userId, "device", previous -> Registration.fromJson("device", userId,
                Json.parseObject(TestSupport.registrationBody(deviceToken, subscriptionsJson)), previous, 0));
    }

    @Test
    void notifiesCompletionsWithTheEventOfTheApp() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("alice"));
        MockFolder folder = j.createFolder("platform");
        FreeStyleProject job = folder.createProject(FreeStyleProject.class, "api");
        register("alice", "ab".repeat(32), "[{\"job\":\"platform\",\"events\":[\"failed\",\"fixed\"]}]");

        job.getBuildersList().add(new FailureBuilder());
        j.buildAndAssertStatus(Result.FAILURE, job);
        FakeRelay.Received failed = relay.await(1).get(0);
        assertEquals("failed", failed.eventType());
        assertEquals("FAILURE", failed.event().get("result"));
        assertEquals("platform/api", failed.event().get("jobFullName"));
        assertEquals("api", failed.event().get("jobDisplayName"));
        assertEquals(1L, failed.event().get("buildNumber"));
        assertEquals(SERVER_ID, failed.event().get("serverId"));
        assertEquals(1L, failed.event().get("v"));
        assertEquals(j.getURL() + "job/platform/job/api/1/", failed.event().get("buildUrl"));
        assertEquals(PushEvent.threadId(SERVER_ID, "platform/api"), failed.request().threadId());
        assertEquals("ab".repeat(32), failed.request().deviceToken());

        job.getBuildersList().clear();
        j.buildAndAssertSuccess(job);
        assertEquals("fixed", relay.await(2).get(1).eventType());
        // A second success is neither subscribed to nor a fix; started is not subscribed either.
        j.buildAndAssertSuccess(job);
        Thread.sleep(500);
        assertEquals(2, relay.received().size());
    }

    @Test
    void onlyNotifiesUsersWhoCanReadTheJob() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("alice", "bob")
                .grant(Item.READ).everywhere().to("alice"));
        FreeStyleProject job = j.createFreeStyleProject("secret");
        register("alice", "ab".repeat(32), "[{\"events\":[\"started\"]}]");
        register("bob", "cd".repeat(32), "[{\"events\":[\"started\"]}]");
        j.buildAndAssertSuccess(job);
        relay.await(1);
        Thread.sleep(500);
        assertEquals(1, relay.received().size());
        assertEquals("ab".repeat(32), relay.received().get(0).request().deviceToken());
    }

    @Test
    void expiresTheRegistrationRefusedByTheRelayAndRetriesTransientFailures() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("alice", "bob"));
        FreeStyleProject job = j.createFreeStyleProject("job");
        register("alice", "ab".repeat(32), "[{\"events\":[\"succeeded\"]}]");
        register("bob", "cd".repeat(32), "[{\"events\":[\"succeeded\"]}]");
        int[] bobAttempts = {0};
        relay.answer(request -> {
            if (request.deviceToken().equals("ab".repeat(32))) {
                return Relay.Outcome.EXPIRED;
            }
            return ++bobAttempts[0] < 3 ? Relay.Outcome.RETRY : Relay.Outcome.DELIVERED;
        });
        j.buildAndAssertSuccess(job);
        relay.await(4);
        Thread.sleep(300);
        assertEquals(4, relay.received().size());
        assertEquals(Registration.Status.EXPIRED, RegistrationStore.get().find("alice", "device").getStatus());
        assertEquals(Registration.Status.ACTIVE, RegistrationStore.get().find("bob", "device").getStatus());

        // An expired registration gets nothing more.
        relay.answer(request -> Relay.Outcome.DELIVERED);
        j.buildAndAssertSuccess(job);
        relay.await(5);
        Thread.sleep(300);
        assertEquals("cd".repeat(32), relay.received().get(4).request().deviceToken());
        assertEquals(5, relay.received().size());
    }

    @Test
    void nothingIsSentWhenPushIsDisabled() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("alice"));
        BouteillerConfiguration.get().setPushEnabled(false);
        register("alice", "ab".repeat(32), "[{\"events\":[\"started\",\"succeeded\"]}]");
        j.buildAndAssertSuccess(j.createFreeStyleProject("job"));
        Thread.sleep(500);
        assertEquals(0, relay.received().size());
    }

    @Test
    void completionTypes() {
        assertEquals(EventType.SUCCEEDED, BuildEventListener.completionType(Result.SUCCESS, null));
        assertEquals(EventType.SUCCEEDED, BuildEventListener.completionType(Result.SUCCESS, Result.SUCCESS));
        assertEquals(EventType.FIXED, BuildEventListener.completionType(Result.SUCCESS, Result.UNSTABLE));
        assertEquals(EventType.FIXED, BuildEventListener.completionType(Result.SUCCESS, Result.FAILURE));
        assertEquals(EventType.UNSTABLE, BuildEventListener.completionType(Result.UNSTABLE, null));
        assertEquals(EventType.ABORTED, BuildEventListener.completionType(Result.ABORTED, null));
        assertEquals(EventType.FAILED, BuildEventListener.completionType(Result.FAILURE, null));
        assertNull(BuildEventListener.completionType(Result.NOT_BUILT, null));
        assertFalse(EventType.STARTED.isSelectedIn(List.of(EventType.SUCCEEDED)));
    }
}
