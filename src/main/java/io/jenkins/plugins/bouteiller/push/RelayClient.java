package io.jenkins.plugins.bouteiller.push;

import hudson.ProxyConfiguration;
import io.jenkins.plugins.bouteiller.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Client of {@code POST /v1/push} of the APNs signing relay. */
public final class RelayClient implements Relay {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** The relay may wait up to 10 s for a free HTTP/2 stream to APNs before answering: leave it room. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** APNs reasons that mean the device token will never work again. */
    private static final Set<String> DEAD_TOKEN_REASONS =
            Set.of("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered", "ExpiredToken");

    private final URI pushUri;
    private final HttpClient client;

    public RelayClient(String relayUrl, boolean useProxy) {
        this.pushUri = URI.create(stripSlash(relayUrl) + "/v1/push");
        this.client = newClient(useProxy);
    }

    private static HttpClient newClient(boolean useProxy) {
        HttpClient.Builder builder =
                useProxy ? ProxyConfiguration.newHttpClientBuilder() : HttpClient.newBuilder();
        return builder.connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String stripSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    /**
     * Checks that the relay answers, with {@code GET /v1/challenge}, the only call that needs no token.
     *
     * @throws IOException with a message for the administrator otherwise
     */
    public static void checkReachable(String relayUrl, boolean useProxy) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(relayUrl) + "/v1/challenge"))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = newClient(useProxy).send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !(Json.parse(response.body()) instanceof Map<?, ?> body)
                || !(body.get("challenge") instanceof String)) {
            throw new IOException("HTTP " + response.statusCode());
        }
    }

    @Override
    public Result send(Request push) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceToken", push.deviceToken());
        body.put("environment", push.environment());
        body.put("accessToken", push.accessToken());
        body.put("payload", push.payload());
        body.put("priority", 10L);
        body.put("threadId", push.threadId());
        HttpRequest request = HttpRequest.newBuilder(pushUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return new Result(Outcome.RETRY, "relay unreachable: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRY, "interrupted");
        }
        return interpret(response.statusCode(), response.body());
    }

    /** Outcome of an answer of the relay. */
    static Result interpret(int status, String body) {
        Map<?, ?> json;
        try {
            json = Json.parse(body) instanceof Map<?, ?> map ? map : Map.of();
        } catch (IllegalArgumentException e) {
            json = Map.of();
        }
        Object error = json.get("error");
        switch (status) {
            case 200 -> {
                long apnsStatus = json.get("apnsStatus") instanceof Long value ? value : 0;
                String reason = json.get("reason") instanceof String value ? value : "";
                if (apnsStatus == 200) {
                    return new Result(Outcome.DELIVERED, "delivered");
                }
                String detail = "APNs " + apnsStatus + " " + reason;
                if (apnsStatus == 410 || DEAD_TOKEN_REASONS.contains(reason)) {
                    return new Result(Outcome.EXPIRED, detail);
                }
                if (apnsStatus == 429 || apnsStatus >= 500) {
                    return new Result(Outcome.RETRY, detail);
                }
                return new Result(Outcome.DROPPED, detail);
            }
            case 401 -> {
                return new Result(Outcome.EXPIRED, "access token refused by the relay");
            }
            case 429, 502, 503, 504 -> {
                return new Result(Outcome.RETRY, "relay " + status + " " + error);
            }
            default -> {
                return new Result(
                        status >= 500 ? Outcome.RETRY : Outcome.DROPPED, "relay " + status + " " + error);
            }
        }
    }
}
