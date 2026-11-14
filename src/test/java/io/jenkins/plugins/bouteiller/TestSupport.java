package io.jenkins.plugins.bouteiller;

import hudson.model.User;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import jenkins.security.ApiTokenProperty;

/** Calls of the API as the app makes them: user name and API token, JSON bodies. */
public final class TestSupport {
    public static final String SERVER_ID = "6E8B1F5C-2D4A-4B7E-9C3F-8A1D2E5F7B90";

    private TestSupport() {}

    /** A registration body with the key 0…31 of PushEventTest. */
    public static String registrationBody(String deviceToken, String subscriptionsJson) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        return "{\"serverId\":\"" + SERVER_ID + "\",\"deviceToken\":\"" + deviceToken
                + "\",\"environment\":\"production\",\"encryptionKey\":\"" + Base64.getEncoder().encodeToString(key)
                + "\",\"relayAccessToken\":\"access-" + deviceToken + "\",\"language\":\"fr\""
                + (subscriptionsJson == null ? "" : ",\"subscriptions\":" + subscriptionsJson) + "}";
    }

    public static String apiToken(String userId) throws IOException {
        return User.getById(userId, true)
                .getProperty(ApiTokenProperty.class)
                .generateNewToken("app")
                .plainValue;
    }

    /** {@code null} credentials call anonymously. */
    public static HttpResponse<String> call(URL root, String method, String path, String userId, String token,
            String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (userId != null) {
            String credentials = userId + ":" + token;
            builder.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
