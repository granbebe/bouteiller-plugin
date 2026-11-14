package io.jenkins.plugins.bouteiller.push;

/** Sends an encrypted event to a device through the APNs signing relay; replaced by a fake in tests. */
public interface Relay {
    /**
     * A notification for one device.
     *
     * @param payload  the encrypted event, {@code base64(nonce || ciphertext || tag)}
     * @param threadId grouping identifier, hashed (cf. {@link PushEvent#threadId})
     */
    record Request(String deviceToken, String environment, String accessToken, String payload, String threadId) {
        @Override
        public String toString() {
            // Never log the tokens.
            return "Request[threadId=" + threadId + ", environment=" + environment + "]";
        }
    }

    /** What to do with the notification. */
    enum Outcome {
        DELIVERED,
        /** The device token or the access token is refused for good: the registration expires. */
        EXPIRED,
        /** A transient failure (relay or APNs unavailable, rate limit): try again later. */
        RETRY,
        /** Refused for another reason: retrying would not help. */
        DROPPED
    }

    record Result(Outcome outcome, String detail) {}

    /** Blocks until the relay answered; never throws. */
    Result send(Request request);
}
