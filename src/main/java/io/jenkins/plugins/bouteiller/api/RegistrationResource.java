package io.jenkins.plugins.bouteiller.api;

import io.jenkins.plugins.bouteiller.BouteillerConfiguration;
import io.jenkins.plugins.bouteiller.EventType;
import io.jenkins.plugins.bouteiller.Registration;
import io.jenkins.plugins.bouteiller.RegistrationStore;
import io.jenkins.plugins.bouteiller.Subscription;
import io.jenkins.plugins.bouteiller.push.PushDispatcher;
import io.jenkins.plugins.bouteiller.push.PushEvent;
import io.jenkins.plugins.bouteiller.push.Relay;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * {@code /registrations/{id}} of the current user: a user only ever sees and changes the registrations of
 * their own devices.
 */
final class RegistrationResource {
    private final String id;

    RegistrationResource(String id) {
        this.id = id;
    }

    /** {@code GET} (state), {@code PUT} (create or update), {@code DELETE} (unregister). */
    void registration(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        String userId = ApiV1.currentUserId();
        if (userId == null) {
            JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        RegistrationStore store = RegistrationStore.get();
        switch (request.getMethod()) {
            case "GET" -> {
                Registration registration = store.find(userId, id);
                if (registration == null) {
                    notFound(response);
                } else {
                    JsonResponses.send(response, HttpServletResponse.SC_OK, registration.toJson());
                }
            }
            case "PUT" -> {
                Map<String, Object> body;
                try {
                    body = JsonResponses.readObject(request);
                    long now = System.currentTimeMillis();
                    boolean[] created = {false};
                    Registration stored = Objects.requireNonNull(store.update(userId, id, previous -> {
                        created[0] = previous == null;
                        return Registration.fromJson(id, userId, body, previous, now);
                    }));
                    JsonResponses.send(
                            response,
                            created[0] ? HttpServletResponse.SC_CREATED : HttpServletResponse.SC_OK,
                            stored.toJson());
                } catch (IllegalArgumentException e) {
                    JsonResponses.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                }
            }
            case "DELETE" -> {
                if (store.remove(userId, id)) {
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                } else {
                    notFound(response);
                }
            }
            default -> JsonResponses.methodNotAllowed(response, "GET, PUT, DELETE");
        }
    }

    /** {@code GET} / {@code PUT /subscriptions}: read or replace the subscriptions. */
    void subscriptions(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        String userId = ApiV1.currentUserId();
        if (userId == null) {
            JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        RegistrationStore store = RegistrationStore.get();
        Registration registration = store.find(userId, id);
        if (registration == null) {
            notFound(response);
            return;
        }
        switch (request.getMethod()) {
            case "GET" -> JsonResponses.send(response, HttpServletResponse.SC_OK, subscriptionsBody(registration));
            case "PUT" -> {
                try {
                    List<Subscription> subscriptions =
                            Subscription.listFromJson(JsonResponses.readObject(request).get("subscriptions"));
                    long now = System.currentTimeMillis();
                    Registration stored = store.update(userId, id, current -> current == null
                            ? null
                            : current.withSubscriptions(subscriptions, now));
                    if (stored == null) {
                        notFound(response);
                    } else {
                        JsonResponses.send(response, HttpServletResponse.SC_OK, subscriptionsBody(stored));
                    }
                } catch (IllegalArgumentException e) {
                    JsonResponses.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                }
            }
            default -> JsonResponses.methodNotAllowed(response, "GET, PUT");
        }
    }

    /**
     * {@code POST /test}: sends a test notification to the device at once and answers with the outcome
     * ({@code delivered}, {@code expired}, {@code retry}, {@code dropped}).
     */
    void test(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        if (!request.getMethod().equals("POST")) {
            JsonResponses.methodNotAllowed(response, "POST");
            return;
        }
        String userId = ApiV1.currentUserId();
        if (userId == null) {
            JsonResponses.error(response, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        Registration registration = RegistrationStore.get().find(userId, id);
        if (registration == null) {
            notFound(response);
            return;
        }
        if (!BouteillerConfiguration.get().isPushEnabled()) {
            JsonResponses.error(response, HttpServletResponse.SC_CONFLICT, "push notifications are disabled");
            return;
        }
        PushEvent event = PushEvent.of(
                "", "Bouteiller", 0, EventType.TEST, null, null, null, null, null, null, null);
        Relay.Result result = PushDispatcher.get().sendTest(registration, event);
        if (result.outcome() == Relay.Outcome.EXPIRED) {
            RegistrationStore.get().expire(registration);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", result.outcome().name().toLowerCase(Locale.ROOT));
        body.put("detail", result.detail());
        body.put("status", registration.getStatus().wireName());
        JsonResponses.send(response, HttpServletResponse.SC_OK, body);
    }

    private static Map<String, Object> subscriptionsBody(Registration registration) {
        return Map.of(
                "subscriptions",
                registration.getSubscriptions().stream().map(Subscription::toJson).toList());
    }

    private static void notFound(StaplerResponse2 response) throws IOException {
        JsonResponses.error(response, HttpServletResponse.SC_NOT_FOUND, "no such registration");
    }
}
