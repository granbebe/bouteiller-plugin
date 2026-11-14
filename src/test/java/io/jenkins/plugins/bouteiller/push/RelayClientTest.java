package io.jenkins.plugins.bouteiller.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.jenkins.plugins.bouteiller.util.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RelayClientTest {
    @Test
    void interpretsTheAnswersOfTheRelay() {
        assertEquals(Relay.Outcome.DELIVERED, outcome(200, "{\"apnsStatus\":200,\"apnsId\":\"x\"}"));
        assertEquals(Relay.Outcome.EXPIRED, outcome(200, "{\"apnsStatus\":410,\"reason\":\"Unregistered\"}"));
        assertEquals(Relay.Outcome.EXPIRED, outcome(200, "{\"apnsStatus\":400,\"reason\":\"BadDeviceToken\"}"));
        assertEquals(Relay.Outcome.RETRY, outcome(200, "{\"apnsStatus\":429,\"reason\":\"TooManyRequests\"}"));
        assertEquals(Relay.Outcome.RETRY, outcome(200, "{\"apnsStatus\":503}"));
        assertEquals(Relay.Outcome.DROPPED, outcome(200, "{\"apnsStatus\":400,\"reason\":\"PayloadTooLarge\"}"));
        assertEquals(Relay.Outcome.EXPIRED, outcome(401, "{\"error\":\"the access token does not grant this device\"}"));
        assertEquals(Relay.Outcome.RETRY, outcome(429, "{\"error\":\"too many\"}"));
        assertEquals(Relay.Outcome.RETRY, outcome(502, "not json"));
        assertEquals(Relay.Outcome.DROPPED, outcome(413, "{\"error\":\"too large\"}"));
        assertEquals(Relay.Outcome.DROPPED, outcome(400, "{\"error\":\"invalid\"}"));
    }

    @Test
    void postsTheNotificationToTheRelay() throws Exception {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/push", exchange -> {
            received.set(Json.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = "{\"apnsStatus\":200}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/challenge", exchange -> {
            byte[] body = "{\"challenge\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Relay.Result result = new RelayClient(url, false)
                    .send(new Relay.Request("ab".repeat(32), "production", "token", "cGF5bG9hZA==", "thread"));
            assertEquals(Relay.Outcome.DELIVERED, result.outcome());
            assertEquals("ab".repeat(32), received.get().get("deviceToken"));
            assertEquals("production", received.get().get("environment"));
            assertEquals("token", received.get().get("accessToken"));
            assertEquals("cGF5bG9hZA==", received.get().get("payload"));
            assertEquals("thread", received.get().get("threadId"));
            assertEquals(10L, received.get().get("priority"));
            RelayClient.checkReachable(url, false);
        } finally {
            server.stop(0);
        }
        String closed = "http://127.0.0.1:" + server.getAddress().getPort();
        assertEquals(Relay.Outcome.RETRY, new RelayClient(closed, false)
                .send(new Relay.Request("ab".repeat(32), "production", "t", "cA==", "x")).outcome());
        assertThrows(IOException.class, () -> RelayClient.checkReachable(closed, false));
    }

    private static Relay.Outcome outcome(int status, String body) {
        return RelayClient.interpret(status, body).outcome();
    }
}
