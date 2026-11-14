package io.jenkins.plugins.bouteiller;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A subscription of a registration: the whole server, or a folder (inherited by the jobs inside)
 * or a job, with the events to notify.
 */
public final class Subscription {
    /** Full name of the folder or job, {@code null} for the whole server. */
    @CheckForNull
    private final String job;

    private final Set<EventType> events;

    public Subscription(@CheckForNull String job, Set<EventType> events) {
        this.job = job == null || job.isEmpty() ? null : job;
        this.events = events.isEmpty() ? EnumSet.noneOf(EventType.class) : EnumSet.copyOf(events);
    }

    @CheckForNull
    public String getJob() {
        return job;
    }

    public Set<EventType> getEvents() {
        return Collections.unmodifiableSet(events);
    }

    /** Whether an event of this type on the job {@code jobFullName} is notified. */
    public boolean matches(String jobFullName, EventType type) {
        boolean inScope = job == null || jobFullName.equals(job) || jobFullName.startsWith(job + "/");
        return inScope && type.isSelectedIn(events);
    }

    /** The JSON of the API: {@code {"job": "folder/job", "events": ["failed", ...]}}, without {@code job} for the server. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        if (job != null) {
            json.put("job", job);
        }
        json.put("events", events.stream().map(EventType::wireName).toList());
        return json;
    }

    /**
     * Reads the subscriptions sent by the app.
     *
     * @throws IllegalArgumentException with a message for the app when the JSON is not valid
     */
    public static List<Subscription> listFromJson(Object value) {
        if (!(value instanceof List<?> array)) {
            throw new IllegalArgumentException("subscriptions must be an array");
        }
        if (array.size() > 1000) {
            throw new IllegalArgumentException("at most 1000 subscriptions");
        }
        return array.stream().map(Subscription::fromJson).toList();
    }

    private static Subscription fromJson(Object value) {
        if (!(value instanceof Map<?, ?> json)) {
            throw new IllegalArgumentException("a subscription must be an object");
        }
        String job = null;
        if (json.get("job") != null) {
            if (!(json.get("job") instanceof String name) || name.length() > 1000) {
                throw new IllegalArgumentException("job must be the full name of a folder or job");
            }
            job = name;
        }
        if (!(json.get("events") instanceof List<?> names)) {
            throw new IllegalArgumentException("events must be an array");
        }
        Set<EventType> events = EnumSet.noneOf(EventType.class);
        for (Object name : names) {
            EventType type = name instanceof String string ? EventType.fromWireName(string) : null;
            if (type == null || type == EventType.TEST) {
                throw new IllegalArgumentException("unknown event " + name);
            }
            events.add(type);
        }
        return new Subscription(job, events);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Subscription that && Objects.equals(job, that.job) && events.equals(that.events);
    }

    @Override
    public int hashCode() {
        return Objects.hash(job, events);
    }
}
