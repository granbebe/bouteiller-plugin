package io.jenkins.plugins.bouteiller.api;

import io.jenkins.plugins.bouteiller.util.Json;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/** Reading and writing the JSON documents of the API. */
final class JsonResponses {
    /** Largest request body accepted: a registration with many subscriptions stays far below. */
    private static final int MAX_BODY_CHARS = 256 * 1024;

    private JsonResponses() {}

    static void send(StaplerResponse2 response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }

    static void error(StaplerResponse2 response, int status, String message) throws IOException {
        send(response, status, Map.of("error", message));
    }

    /** Answers 405 with the allowed methods. */
    static void methodNotAllowed(StaplerResponse2 response, String allowed) throws IOException {
        response.setHeader("Allow", allowed);
        error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "allowed methods: " + allowed);
    }

    /**
     * The JSON object of the request body.
     *
     * @throws IllegalArgumentException with a message for the app when the body is not a JSON object
     */
    static Map<String, Object> readObject(StaplerRequest2 request) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            char[] buffer = new char[8192];
            for (int count; (count = reader.read(buffer)) != -1; ) {
                text.append(buffer, 0, count);
                if (text.length() > MAX_BODY_CHARS) {
                    throw new IllegalArgumentException("the request body is too large");
                }
            }
        }
        return Json.parseObject(text.toString());
    }
}
