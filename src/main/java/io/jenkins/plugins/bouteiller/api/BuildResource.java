package io.jenkins.plugins.bouteiller.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.bouteiller.Pipelines;
import io.jenkins.plugins.bouteiller.pipeline.PipelineRuns;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * {@code /job/{path}/{n}/stages} and {@code /job/{path}/{n}/inputs}: the path of the job as in the URLs of
 * Jenkins ({@code job/folder/job/name}), with the permissions of the current user.
 */
final class BuildResource {
    /** A build located by the URL: job full name, number and what is asked of it. */
    record Location(String jobFullName, int number, String resource) {}

    private BuildResource() {}

    /**
     * Reads {@code folder/job/name/42/stages}, the rest of the path after {@code /job/}, whose job names are encoded
     * as in the URLs of Jenkins. {@code null} when it is not such a path.
     */
    @CheckForNull
    static Location parse(String restOfPath) {
        String[] segments = restOfPath.replaceFirst("^/", "").split("/", -1);
        // name ( "job" name )* number resource
        if (segments.length < 3 || (segments.length - 3) % 2 != 0) {
            return null;
        }
        List<String> names = new ArrayList<>();
        names.add(decode(segments[0]));
        for (int index = 1; index < segments.length - 2; index += 2) {
            if (!segments[index].equals("job")) {
                return null;
            }
            names.add(decode(segments[index + 1]));
        }
        if (names.stream().anyMatch(String::isEmpty)) {
            return null;
        }
        int number;
        try {
            number = Integer.parseInt(segments[segments.length - 2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new Location(String.join("/", names), number, segments[segments.length - 1]);
    }

    /** Percent-decoding only: {@code +} stays, as in a path. */
    private static String decode(String segment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (character == '%' && index + 2 < segment.length()) {
                try {
                    bytes.write(Integer.parseInt(segment.substring(index + 1, index + 3), 16));
                    index += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // Not an escape: kept as is.
                }
            }
            byte[] encoded = String.valueOf(character).getBytes(StandardCharsets.UTF_8);
            bytes.write(encoded, 0, encoded.length);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    static void serve(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        if (!request.getMethod().equals("GET")) {
            JsonResponses.methodNotAllowed(response, "GET");
            return;
        }
        Location location = parse(request.getRestOfPath());
        if (location == null || !(location.resource().equals("stages") || location.resource().equals("inputs"))) {
            JsonResponses.error(response, HttpServletResponse.SC_NOT_FOUND, "no such resource");
            return;
        }
        // Null without the Item/Read permission: the job does not exist for this user.
        Job<?, ?> job = Jenkins.get().getItemByFullName(location.jobFullName(), Job.class);
        Run<?, ?> run = job == null ? null : job.getBuildByNumber(location.number());
        if (run == null) {
            JsonResponses.error(response, HttpServletResponse.SC_NOT_FOUND, "no such build");
            return;
        }
        if (!Pipelines.isInstalled() || !PipelineRuns.isPipeline(run)) {
            JsonResponses.error(response, HttpServletResponse.SC_NOT_FOUND, "not a Pipeline build");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // Only PipelineRuns is named here, and only once Pipeline is known to be installed.
        if (location.resource().equals("stages")) {
            body.put("complete", !run.isBuilding());
            body.put("stages", PipelineRuns.stagesJson(run));
        } else {
            try {
                body.put("inputs", PipelineRuns.inputsJson(run, Pipelines.hasInputStep()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                JsonResponses.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "interrupted");
                return;
            }
        }
        JsonResponses.send(response, HttpServletResponse.SC_OK, body);
    }
}
