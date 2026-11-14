package io.jenkins.plugins.bouteiller.push;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.bouteiller.EventType;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An event as the app reads it once decrypted, without the {@code serverId} of the registration, added
 * per device by {@link #toJson(String)}.
 *
 * @param jobFullName        full name of the job, {@code /} between folders
 * @param result             result of a completed build as Jenkins names it ({@code SUCCESS}, {@code FAILURE}…)
 * @param inputHasParameters whether the pending input asks for parameters
 * @param culprit            author of the last commit, or the user who started the build
 * @param occurredAt         when the plugin saw the event, in milliseconds since 1970
 */
public record PushEvent(
        String id,
        String jobFullName,
        String jobDisplayName,
        int buildNumber,
        EventType type,
        @CheckForNull String result,
        @CheckForNull String failedStage,
        @CheckForNull String inputId,
        @CheckForNull String inputMessage,
        @CheckForNull Boolean inputHasParameters,
        @CheckForNull String culprit,
        @CheckForNull String buildUrl,
        long occurredAt) {

    /** Version of the format, read by the app before the other fields. */
    public static final int FORMAT_VERSION = 1;

    /** Bound of the input message, the only free text of unbounded length, to stay within the 4 KB of APNs. */
    static final int INPUT_MESSAGE_MAX_BYTES = 1500;

    public PushEvent {
        inputMessage = inputMessage == null ? null : truncateUtf8(inputMessage, INPUT_MESSAGE_MAX_BYTES);
    }

    /** An event with a new identifier, seen now. */
    public static PushEvent of(
            String jobFullName,
            String jobDisplayName,
            int buildNumber,
            EventType type,
            @CheckForNull String result,
            @CheckForNull String failedStage,
            @CheckForNull String inputId,
            @CheckForNull String inputMessage,
            @CheckForNull Boolean inputHasParameters,
            @CheckForNull String culprit,
            @CheckForNull String buildUrl) {
        return new PushEvent(
                UUID.randomUUID().toString(),
                jobFullName,
                jobDisplayName,
                buildNumber,
                type,
                result,
                failedStage,
                inputId,
                inputMessage,
                inputHasParameters,
                culprit,
                buildUrl,
                System.currentTimeMillis());
    }

    /** The JSON encrypted for one device; absent values are left out. */
    public Map<String, Object> toJson(String serverId) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v", FORMAT_VERSION);
        json.put("id", id);
        json.put("serverId", serverId);
        json.put("jobFullName", jobFullName);
        json.put("jobDisplayName", jobDisplayName);
        json.put("buildNumber", buildNumber);
        json.put("eventType", type.wireName());
        putIfPresent(json, "result", result);
        putIfPresent(json, "failedStage", failedStage);
        putIfPresent(json, "inputId", inputId);
        putIfPresent(json, "inputMessage", inputMessage);
        if (inputHasParameters != null) {
            json.put("inputHasParameters", inputHasParameters);
        }
        putIfPresent(json, "culprit", culprit);
        putIfPresent(json, "buildUrl", buildUrl);
        json.put("occurredAt", occurredAt);
        return json;
    }

    private static void putIfPresent(Map<String, Object> json, String key, @CheckForNull String value) {
        if (value != null) {
            json.put(key, value);
        }
    }

    /**
     * Grouping identifier of the notifications of a job ({@code thread-id} of APNs): a hash of the server and the job,
     * so that the relay and APNs do not see the job name.
     */
    public static String threadId(String serverId, String jobFullName) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((serverId + "\n" + jobFullName).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 22);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code text} cut to at most {@code maxBytes} UTF-8 bytes, ellipsis included, without splitting a character. */
    static String truncateUtf8(String text, int maxBytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        String ellipsis = "…";
        int budget = maxBytes - ellipsis.getBytes(StandardCharsets.UTF_8).length;
        CharsetEncoder encoder = StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        ByteBuffer buffer = ByteBuffer.allocate(budget);
        CharBuffer input = CharBuffer.wrap(text);
        // The encoder stops before a character (or surrogate pair) that does not fit.
        encoder.encode(input, buffer, true);
        return text.substring(0, input.position()) + ellipsis;
    }
}
