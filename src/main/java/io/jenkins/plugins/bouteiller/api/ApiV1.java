package io.jenkins.plugins.bouteiller.api;

import hudson.PluginWrapper;
import hudson.model.User;
import io.jenkins.plugins.bouteiller.BouteillerConfiguration;
import io.jenkins.plugins.bouteiller.Pipelines;
import io.jenkins.plugins.bouteiller.Registration;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.security.stapler.StaplerAccessibleType;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/** Version 1 of the API of the app, {@code /bouteiller/api/v1}. */
@StaplerAccessibleType
public final class ApiV1 {
    /** {@code GET /info}: version of the plugin and available features. */
    public void doInfo(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        if (!request.getMethod().equals("GET")) {
            JsonResponses.methodNotAllowed(response, "GET");
            return;
        }
        PluginWrapper plugin = Jenkins.get().getPluginManager().getPlugin("bouteiller");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", plugin == null ? "unknown" : plugin.getVersion());
        body.put("apiVersion", 1L);
        List<String> features = new ArrayList<>();
        if (BouteillerConfiguration.get().isPushEnabled()) {
            features.add("push");
        }
        if (Pipelines.isInstalled()) {
            features.add("stages");
            features.add("inputs");
        }
        body.put("features", features);
        JsonResponses.send(response, HttpServletResponse.SC_OK, body);
    }

    /**
     * {@code /registrations/{id}}, {@code /registrations/{id}/subscriptions} and {@code /registrations/{id}/test}:
     * the registrations of the current user. Routed here rather than through Stapler objects so that no URL needs a
     * trailing slash.
     */
    public void doRegistrations(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        String[] segments = request.getRestOfPath().replaceFirst("^/", "").split("/", -1);
        if (segments.length == 0 || segments.length > 2 || !Registration.ID.matcher(segments[0]).matches()) {
            JsonResponses.error(response, HttpServletResponse.SC_NOT_FOUND, "no such resource");
            return;
        }
        RegistrationResource resource = new RegistrationResource(segments[0]);
        String child = segments.length == 2 ? segments[1] : "";
        switch (child) {
            case "" -> resource.registration(request, response);
            case "subscriptions" -> resource.subscriptions(request, response);
            case "test" -> resource.test(request, response);
            default -> JsonResponses.error(response, HttpServletResponse.SC_NOT_FOUND, "no such resource");
        }
    }

    /** {@code /job/{path}/{n}/stages} and {@code /job/{path}/{n}/inputs} of Pipeline builds. */
    public void doJob(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        BuildResource.serve(request, response);
    }

    /** The identifier of the Jenkins user calling, {@code null} when anonymous. */
    static String currentUserId() {
        User user = User.current();
        return user == null ? null : user.getId();
    }
}
