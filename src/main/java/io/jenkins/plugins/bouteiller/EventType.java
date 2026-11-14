package io.jenkins.plugins.bouteiller;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.Collection;

/** Types of the events sent to the app, with their names in the JSON of the event and of the API. */
public enum EventType {
    STARTED("started"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    UNSTABLE("unstable"),
    /** Back to success after a failed or unstable build. */
    FIXED("fixed"),
    ABORTED("aborted"),
    INPUT_PENDING("inputPending"),
    /** Notification asked by the app to check its registration; never subscribed to. */
    TEST("test");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** The type with this JSON name, {@code null} when unknown. */
    @CheckForNull
    public static EventType fromWireName(String name) {
        for (EventType type : values()) {
            if (type.wireName.equals(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Whether a subscription to {@code events} is notified of this event: a subscription to successes also gets the
     * fixes, as in the app.
     */
    public boolean isSelectedIn(Collection<EventType> events) {
        return events.contains(this) || (this == FIXED && events.contains(SUCCEEDED));
    }
}
