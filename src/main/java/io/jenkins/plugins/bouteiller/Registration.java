package io.jenkins.plugins.bouteiller;

import hudson.util.Secret;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The registration of one device of a Jenkins user. Device token, encryption key and relay access token are
 * kept encrypted with {@link Secret}.
 *
 * <p>Immutable but for its status: an update from the app replaces the registration.
 */
public final class Registration {
    /** Identifier chosen by the app, unique per device; scoped to the Jenkins user. */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final Pattern DEVICE_TOKEN = Pattern.compile("[0-9a-fA-F]{32,200}");
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*");

    /** {@code active}, or {@code expired} once the relay refused the device token or the access token. */
    public enum Status {
        ACTIVE,
        EXPIRED;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String id;
    private final String userId;
    /** Identifier of the server in the app, copied into every event so that the app knows which server it is about. */
    private final String serverId;

    private final Secret deviceToken;
    private final String environment;
    private final Secret encryptionKey;
    private final Secret relayAccessToken;
    private final String language;
    private final List<Subscription> subscriptions;
    private final long createdAt;
    private final long updatedAt;
    private volatile Status status;

    Registration(String id, String userId, String serverId, Secret deviceToken, String environment,
            Secret encryptionKey, Secret relayAccessToken, String language, List<Subscription> subscriptions,
            long createdAt, long updatedAt, Status status) {
        this.id = id;
        this.userId = userId;
        this.serverId = serverId;
        this.deviceToken = deviceToken;
        this.environment = environment;
        this.encryptionKey = encryptionKey;
        this.relayAccessToken = relayAccessToken;
        this.language = language;
        this.subscriptions = List.copyOf(subscriptions);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.status = status;
    }

    /**
     * Reads the body of {@code PUT /registrations/{id}}. Subscriptions are optional: without them, those of
     * {@code previous} are kept.
     *
     * @param previous the registration this one replaces, {@code null} for a new one
     * @throws IllegalArgumentException with a message for the app when the JSON is not valid
     */
    public static Registration fromJson(String id, String userId, Map<String, Object> json, Registration previous, long now) {
        String serverId = requiredString(json, "serverId");
        try {
            UUID.fromString(serverId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("serverId must be a UUID");
        }
        String deviceToken = requiredString(json, "deviceToken");
        if (!DEVICE_TOKEN.matcher(deviceToken).matches() || deviceToken.length() % 2 != 0) {
            throw new IllegalArgumentException("deviceToken must be the APNs device token in hexadecimal");
        }
        String environment = requiredString(json, "environment");
        if (!environment.equals("development") && !environment.equals("production")) {
            throw new IllegalArgumentException("environment must be development or production");
        }
        String encryptionKey = requiredString(json, "encryptionKey");
        if (decodeKey(encryptionKey) == null) {
            throw new IllegalArgumentException("encryptionKey must be an AES-256 key in base64");
        }
        String accessToken = requiredString(json, "relayAccessToken");
        if (accessToken.length() > 4096) {
            throw new IllegalArgumentException("relayAccessToken is too long");
        }
        String language = json.get("language") == null ? "en" : requiredString(json, "language");
        if (!LANGUAGE.matcher(language).matches()) {
            throw new IllegalArgumentException("language must be a language tag");
        }
        List<Subscription> subscriptions;
        if (json.get("subscriptions") != null) {
            subscriptions = Subscription.listFromJson(json.get("subscriptions"));
        } else {
            subscriptions = previous == null ? List.of() : previous.subscriptions;
        }
        return new Registration(id, userId, serverId, Secret.fromString(deviceToken.toLowerCase(Locale.ROOT)),
                environment, Secret.fromString(encryptionKey), Secret.fromString(accessToken), language,
                subscriptions, previous == null ? now : previous.createdAt, now, Status.ACTIVE);
    }

    private static String requiredString(Map<String, Object> json, String key) {
        if (!(json.get(key) instanceof String value) || value.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    /** The 32 bytes of a key in base64, {@code null} when it is not one. */
    private static byte[] decodeKey(String base64) {
        try {
            byte[] key = Base64.getDecoder().decode(base64);
            return key.length == 32 ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Registration withSubscriptions(List<Subscription> newSubscriptions, long now) {
        return new Registration(id, userId, serverId, deviceToken, environment, encryptionKey, relayAccessToken,
                language, newSubscriptions, createdAt, now, status);
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getServerId() {
        return serverId;
    }

    /** The APNs device token in hexadecimal; not named as a getter so that no page or API exposes it. */
    @Restricted(NoExternalUse.class)
    public String deviceToken() {
        return deviceToken.getPlainText();
    }

    public String getEnvironment() {
        return environment;
    }

    @Restricted(NoExternalUse.class)
    public byte[] encryptionKey() {
        return decodeKey(encryptionKey.getPlainText());
    }

    @Restricted(NoExternalUse.class)
    public String relayAccessToken() {
        return relayAccessToken.getPlainText();
    }

    public String getLanguage() {
        return language;
    }

    public List<Subscription> getSubscriptions() {
        return subscriptions;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /** For the administration page. */
    public java.util.Date getUpdatedAtDate() {
        return new java.util.Date(updatedAt);
    }

    public Status getStatus() {
        return status;
    }

    void setStatus(Status status) {
        this.status = status;
    }

    /** Whether an event of this type on the job is notified to this device. */
    public boolean isSubscribed(String jobFullName, EventType type) {
        return subscriptions.stream().anyMatch(subscription -> subscription.matches(jobFullName, type));
    }

    /** The registration as the API returns it: no secret. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("serverId", serverId);
        json.put("status", status.wireName());
        json.put("environment", environment);
        json.put("language", language);
        json.put("subscriptions", subscriptionsJson());
        json.put("createdAt", createdAt);
        json.put("updatedAt", updatedAt);
        return json;
    }

    List<Map<String, Object>> subscriptionsJson() {
        return subscriptions.stream().map(Subscription::toJson).toList();
    }

    /** Readable end of the device token, to tell the devices of a user apart on the administration page. */
    public String getDeviceTokenSuffix() {
        String token = deviceToken();
        return "…" + token.substring(Math.max(0, token.length() - 8));
    }
}
