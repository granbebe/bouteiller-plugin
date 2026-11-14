package io.jenkins.plugins.bouteiller.api;

import static io.jenkins.plugins.bouteiller.TestSupport.apiToken;
import static io.jenkins.plugins.bouteiller.TestSupport.call;
import static io.jenkins.plugins.bouteiller.TestSupport.registrationBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import io.jenkins.plugins.bouteiller.BouteillerConfiguration;
import io.jenkins.plugins.bouteiller.Registration;
import io.jenkins.plugins.bouteiller.RegistrationStore;
import io.jenkins.plugins.bouteiller.push.FakeRelay;
import io.jenkins.plugins.bouteiller.push.PushDispatcher;
import io.jenkins.plugins.bouteiller.push.Relay;
import io.jenkins.plugins.bouteiller.util.Json;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RegistrationApiTest {
    private static final String TOKEN_A = "ab".repeat(32);
    private static final String PATH = "bouteiller/api/v1/registrations/device-1";

    private JenkinsRule j;
    private URL root;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        root = j.getURL();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("alice", "bob"));
        aliceToken = apiToken("alice");
        bobToken = apiToken("bob");
    }

    @Test
    void info() throws Exception {
        HttpResponse<String> response = call(root, "GET", "bouteiller/api/v1/info", "alice", aliceToken, null);
        assertEquals(200, response.statusCode());
        Map<String, Object> body = Json.parseObject(response.body());
        assertNotNull(body.get("version"));
        assertEquals(List.of("push", "stages", "inputs"), body.get("features"));
        BouteillerConfiguration.get().setPushEnabled(false);
        body = Json.parseObject(call(root, "GET", "bouteiller/api/v1/info", "alice", aliceToken, null).body());
        assertEquals(List.of("stages", "inputs"), body.get("features"));
    }

    @Test
    void anonymousCallsAreRefused() throws Exception {
        // Without Overall/Read, Jenkins itself refuses; with it, the API asks for a user.
        assertEquals(403, call(root, "GET", PATH, null, null, null).statusCode());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toEveryone());
        assertEquals(401, call(root, "PUT", PATH, null, null, registrationBody(TOKEN_A, null)).statusCode());
    }

    @Test
    void createReadUpdateDelete() throws Exception {
        HttpResponse<String> created = call(root, "PUT", PATH, "alice", aliceToken,
                registrationBody(TOKEN_A, "[{\"job\":\"platform\",\"events\":[\"failed\"]}]"));
        assertEquals(201, created.statusCode(), created.body());
        Map<String, Object> body = Json.parseObject(created.body());
        assertEquals("device-1", body.get("id"));
        assertEquals("active", body.get("status"));
        assertEquals("fr", body.get("language"));
        assertFalse(created.body().contains(TOKEN_A), "the API never returns the device token");
        assertFalse(created.body().contains("access-"), "the API never returns the access token");

        // An update without subscriptions keeps them.
        HttpResponse<String> updated = call(root, "PUT", PATH, "alice", aliceToken, registrationBody("cd".repeat(32), null));
        assertEquals(200, updated.statusCode());
        assertEquals(List.of(Map.of("job", "platform", "events", List.of("failed"))),
                Json.parseObject(updated.body()).get("subscriptions"));
        Registration stored = RegistrationStore.get().find("alice", "device-1");
        assertEquals("cd".repeat(32), stored.deviceToken());
        assertEquals("access-" + "cd".repeat(32), stored.relayAccessToken());

        HttpResponse<String> subscriptions = call(root, "PUT", PATH + "/subscriptions", "alice", aliceToken,
                "{\"subscriptions\":[{\"events\":[\"inputPending\"]}]}");
        assertEquals(200, subscriptions.statusCode(), subscriptions.body());
        assertEquals("{\"subscriptions\":[{\"events\":[\"inputPending\"]}]}",
                call(root, "GET", PATH + "/subscriptions", "alice", aliceToken, null).body());

        assertEquals(204, call(root, "DELETE", PATH, "alice", aliceToken, null).statusCode());
        assertEquals(404, call(root, "GET", PATH, "alice", aliceToken, null).statusCode());
        assertEquals(404, call(root, "DELETE", PATH, "alice", aliceToken, null).statusCode());
    }

    @Test
    void secretsAreEncryptedOnDisk() throws Exception {
        call(root, "PUT", PATH, "alice", aliceToken, registrationBody(TOKEN_A, null));
        String xml = new java.io.File(j.jenkins.getRootDir(), RegistrationStore.class.getName() + ".xml").exists()
                ? java.nio.file.Files.readString(
                        new java.io.File(j.jenkins.getRootDir(), RegistrationStore.class.getName() + ".xml").toPath())
                : "";
        assertTrue(xml.contains("device-1"), xml);
        assertFalse(xml.contains(TOKEN_A));
        assertFalse(xml.contains("access-"));
    }

    @Test
    void usersOnlySeeTheirOwnRegistrations() throws Exception {
        call(root, "PUT", PATH, "alice", aliceToken, registrationBody(TOKEN_A, null));
        assertEquals(404, call(root, "GET", PATH, "bob", bobToken, null).statusCode());
        assertEquals(404, call(root, "DELETE", PATH, "bob", bobToken, null).statusCode());
        assertEquals(201, call(root, "PUT", PATH, "bob", bobToken, registrationBody("ef".repeat(32), null)).statusCode());
        assertEquals(TOKEN_A, RegistrationStore.get().find("alice", "device-1").deviceToken());
        assertEquals(2, RegistrationStore.get().all().size());
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        assertEquals(400, call(root, "PUT", PATH, "alice", aliceToken, "not json").statusCode());
        assertEquals(400, call(root, "PUT", PATH, "alice", aliceToken,
                registrationBody("not-hex", null)).statusCode());
        assertEquals(400, call(root, "PUT", PATH, "alice", aliceToken,
                registrationBody(TOKEN_A, "[{\"events\":[\"exploded\"]}]")).statusCode());
        HttpResponse<String> badKey = call(root, "PUT", PATH, "alice", aliceToken,
                registrationBody(TOKEN_A, null).replaceAll("\"encryptionKey\":\"[^\"]*\"", "\"encryptionKey\":\"c2hvcnQ=\""));
        assertEquals(400, badKey.statusCode());
        assertTrue(badKey.body().contains("encryptionKey"), badKey.body());
        assertEquals(404, call(root, "GET", "bouteiller/api/v1/registrations/bad%20id", "alice", aliceToken, null)
                .statusCode());
        assertEquals(405, call(root, "POST", PATH, "alice", aliceToken, "{}").statusCode());
        assertNull(RegistrationStore.get().find("alice", "device-1"));
    }

    @Test
    void testNotification() throws Exception {
        FakeRelay relay = new FakeRelay();
        PushDispatcher.get().setRelayFactory(configuration -> relay);
        assertEquals(404, call(root, "POST", PATH + "/test", "alice", aliceToken, null).statusCode());
        call(root, "PUT", PATH, "alice", aliceToken, registrationBody(TOKEN_A, null));

        HttpResponse<String> response = call(root, "POST", PATH + "/test", "alice", aliceToken, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("delivered", Json.parseObject(response.body()).get("outcome"));
        FakeRelay.Received received = relay.received().get(0);
        assertEquals("test", received.eventType());
        assertEquals(TOKEN_A, received.request().deviceToken());
        assertEquals("access-" + TOKEN_A, received.request().accessToken());

        relay.answer(request -> Relay.Outcome.EXPIRED);
        Map<String, Object> expired = Json.parseObject(call(root, "POST", PATH + "/test", "alice", aliceToken, null).body());
        assertEquals("expired", expired.get("outcome"));
        assertEquals("expired", expired.get("status"));
        // The app renews the registration: active again.
        call(root, "PUT", PATH, "alice", aliceToken, registrationBody(TOKEN_A, null));
        assertEquals(Registration.Status.ACTIVE, RegistrationStore.get().find("alice", "device-1").getStatus());
    }
}
