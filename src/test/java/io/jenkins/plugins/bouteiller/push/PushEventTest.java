package io.jenkins.plugins.bouteiller.push;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bouteiller.EventType;
import io.jenkins.plugins.bouteiller.util.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PushEventTest {
    /**
     * Test vector shared with the iOS app, whose tests decrypt it with CryptoKit: the app must decrypt what the plugin
     * encrypts. Key: bytes 0 to 31; nonce: bytes 0 to 11. A change of the event format changes it on both sides.
     */
    private static final String VECTOR_PLAINTEXT = "{\"v\":1,\"id\":\"vector\",\"serverId\":\"6E8B1F5C-2D4A-4B7E-9C3F-8A1D2E5F7B90\","
            + "\"jobFullName\":\"platform/payments-api/main\",\"jobDisplayName\":\"main\",\"buildNumber\":42,"
            + "\"eventType\":\"inputPending\",\"inputId\":\"Deploy\",\"inputMessage\":\"Déployer en production ?\","
            + "\"culprit\":\"Jane Doe\",\"buildUrl\":\"https://jenkins.example.com/job/platform/job/payments-api/job/main/42/\","
            + "\"occurredAt\":1793620800123}";

    private static final String VECTOR_CIPHERTEXT = "AAECAwQFBgcICQoLPCCgOf/U7jnkJbWxk58dDve59RbcWSwZShGA91QNIogjJuvE7fBUrTeJTam8xgUMrG4loGOVkJwSr2soXNGw27YLhEPj8wpDdjvIKJrjYqZZ56xATEEjBJyJ+KMKnZb9qoE0g8o1uzf/bFedJRRIEZhLQMGMp2HzPo87rPuoD2PecDrk2baiPSrNasNIsyCmeIGOV1BjsXC4bcRVn6SMgOfD52WM3fZczg2tj6vQ49X5p6RFqYdR1QuvtbM4oZXJMQjvimGyr+sbdLqIyjStyYQNixdaZZjLU9VZbjEk4IMmUfTKiABzVz0jzWvBeBM/iKybWb9T/MlnGQ4HqaZgy3W/l7DZtS0cW/0Qct5BXl+o2Oj3ouP12wFcV34lnt8lamqwO5Sgtnb/TFY/JPbPvregNDxm5KN27FdyZPoSL4arug9L8g0dh9eTVkEtujII/Hj46f196yd9kVdEoZMnnDpai6vgMDO/ApWVFK6GVBq0xS9J38MzvB67+e6qcv2tgnq106jq+tw=";

    @Test
    void serializesTheSharedVector() throws Exception {
        PushEvent event = new PushEvent("vector", "platform/payments-api/main", "main", 42, EventType.INPUT_PENDING,
                null, null, "Deploy", "Déployer en production ?", null, "Jane Doe",
                "https://jenkins.example.com/job/platform/job/payments-api/job/main/42/", 1_793_620_800_123L);
        String json = Json.write(event.toJson("6E8B1F5C-2D4A-4B7E-9C3F-8A1D2E5F7B90"));
        assertEquals(VECTOR_PLAINTEXT, json);
        assertEquals(VECTOR_CIPHERTEXT,
                EventCipher.encrypt(sequence(32), sequence(12), json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void roundTripsAndRefusesTampering() throws Exception {
        byte[] key = sequence(32);
        String sealed = EventCipher.encrypt(key, "hello".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), EventCipher.decrypt(key, sealed));
        byte[] bytes = Base64.getDecoder().decode(sealed);
        bytes[14] ^= 1;
        assertThrows(GeneralSecurityException.class,
                () -> EventCipher.decrypt(key, Base64.getEncoder().encodeToString(bytes)));
    }

    @Test
    void keepsStringsThatLookLikeJson() {
        PushEvent event = new PushEvent("id", "a/b", "b", 1, EventType.INPUT_PENDING, null, null, "Deploy",
                "[prod] {region: eu}", true, null, null, 0);
        Map<?, ?> json = (Map<?, ?>) Json.parse(Json.write(event.toJson("s")));
        assertEquals("[prod] {region: eu}", json.get("inputMessage"));
        assertEquals(Boolean.TRUE, json.get("inputHasParameters"));
        assertFalse(json.containsKey("result"));
    }

    @Test
    void truncatesLongInputMessagesWithoutSplittingCharacters() {
        String message = "é".repeat(1000) + "😀";
        PushEvent event = new PushEvent("id", "a", "a", 1, EventType.INPUT_PENDING, null, null, "x", message, null,
                null, null, 0);
        byte[] bytes = event.inputMessage().getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length <= PushEvent.INPUT_MESSAGE_MAX_BYTES, "length " + bytes.length);
        assertTrue(event.inputMessage().endsWith("é…"));
        assertEquals("short", PushEvent.truncateUtf8("short", 10));
        assertEquals("ab…", PushEvent.truncateUtf8("ab😀cd", 7));
    }

    @Test
    void hashesTheThreadId() {
        String threadId = PushEvent.threadId("6E8B1F5C-2D4A-4B7E-9C3F-8A1D2E5F7B90", "platform/payments-api/main");
        assertEquals(22, threadId.length());
        assertTrue(threadId.matches("[A-Za-z0-9_-]+"));
        assertFalse(threadId.equals(PushEvent.threadId("6E8B1F5C-2D4A-4B7E-9C3F-8A1D2E5F7B90", "other")));
    }

    static byte[] sequence(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) index;
        }
        return bytes;
    }
}
