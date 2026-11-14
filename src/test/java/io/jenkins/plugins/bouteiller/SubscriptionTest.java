package io.jenkins.plugins.bouteiller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bouteiller.util.Json;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubscriptionTest {
    @Test
    void matchesTheJobAndTheJobsInsideAFolder() {
        Subscription folder = new Subscription("platform", EnumSet.of(EventType.FAILED));
        assertTrue(folder.matches("platform", EventType.FAILED));
        assertTrue(folder.matches("platform/payments-api/main", EventType.FAILED));
        assertFalse(folder.matches("platform-legacy/api", EventType.FAILED));
        assertFalse(folder.matches("platform/api", EventType.STARTED));
        Subscription server = new Subscription(null, EnumSet.of(EventType.SUCCEEDED));
        assertTrue(server.matches("anything", EventType.SUCCEEDED));
        // A subscription to successes also gets the fixes, as in the app.
        assertTrue(server.matches("anything", EventType.FIXED));
    }

    @Test
    void readsAndWritesTheJsonOfTheApi() {
        List<Subscription> subscriptions = Subscription.listFromJson(Json.parse(
                "[{\"job\":\"platform\",\"events\":[\"failed\",\"inputPending\"]},{\"events\":[\"started\"]}]"));
        assertEquals(new Subscription("platform", EnumSet.of(EventType.FAILED, EventType.INPUT_PENDING)),
                subscriptions.get(0));
        assertEquals(new Subscription(null, EnumSet.of(EventType.STARTED)), subscriptions.get(1));
        assertEquals("{\"job\":\"platform\",\"events\":[\"failed\",\"inputPending\"]}",
                Json.write(subscriptions.get(0).toJson()));
        assertEquals("{\"events\":[\"started\"]}", Json.write(subscriptions.get(1).toJson()));
    }

    @Test
    void refusesInvalidSubscriptions() {
        assertThrows(IllegalArgumentException.class, () -> Subscription.listFromJson(Json.parse("{}")));
        assertThrows(IllegalArgumentException.class,
                () -> Subscription.listFromJson(Json.parse("[{\"events\":[\"exploded\"]}]")));
        assertThrows(IllegalArgumentException.class,
                () -> Subscription.listFromJson(Json.parse("[{\"events\":[\"test\"]}]")));
        assertThrows(IllegalArgumentException.class, () -> Subscription.listFromJson(Json.parse("[{\"job\":3,\"events\":[]}]")));
    }
}
