package io.jenkins.plugins.bouteiller;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.BulkChange;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * The registrations of every device, in {@code JENKINS_HOME/io.jenkins.plugins.bouteiller.RegistrationStore.xml}.
 * Registrations are identified by their Jenkins user and the identifier chosen by the app.
 */
@Extension
public final class RegistrationStore implements Saveable {
    private static final Logger LOGGER = Logger.getLogger(RegistrationStore.class.getName());

    private final List<Registration> registrations = new ArrayList<>();
    private transient boolean loaded;

    public static RegistrationStore get() {
        return Jenkins.get().getExtensionList(RegistrationStore.class).getInstance(RegistrationStore.class);
    }

    private XmlFile file() {
        return new XmlFile(Jenkins.XSTREAM2, new File(Jenkins.get().getRootDir(), getClass().getName() + ".xml"));
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        XmlFile file = file();
        if (file.exists()) {
            try {
                file.unmarshal(this);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot read the Bouteiller registrations from " + file, e);
            }
        }
    }

    @Override
    public synchronized void save() throws IOException {
        if (BulkChange.contains(this)) {
            return;
        }
        XmlFile file = file();
        file.write(this);
        SaveableListener.fireOnChange(this, file);
    }

    /** Every registration, sorted by user then creation. */
    public synchronized List<Registration> all() {
        ensureLoaded();
        return registrations.stream()
                .sorted(Comparator.comparing(Registration::getUserId).thenComparing(Registration::getCreatedAt))
                .toList();
    }

    @CheckForNull
    public synchronized Registration find(String userId, String id) {
        ensureLoaded();
        return registrations.stream()
                .filter(registration -> registration.getUserId().equals(userId) && registration.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Replaces the registration {@code userId}/{@code id} by what {@code update} returns from the current one
     * ({@code null} when absent), atomically.
     *
     * @return the stored registration, {@code null} when {@code update} returned {@code null} (nothing changes)
     */
    @CheckForNull
    public synchronized Registration update(String userId, String id, UnaryOperator<Registration> update)
            throws IOException {
        ensureLoaded();
        Registration current = find(userId, id);
        Registration updated = update.apply(current);
        if (updated == null) {
            return null;
        }
        if (current != null) {
            registrations.set(registrations.indexOf(current), updated);
        } else {
            registrations.add(updated);
        }
        save();
        return updated;
    }

    /** @return whether there was such a registration */
    public synchronized boolean remove(String userId, String id) throws IOException {
        ensureLoaded();
        Registration current = find(userId, id);
        if (current == null) {
            return false;
        }
        registrations.remove(current);
        save();
        return true;
    }

    /**
     * Marks a registration as expired, if it was not replaced in the meantime (the app may have renewed it while the
     * notification was on its way).
     */
    public synchronized void expire(Registration registration) {
        ensureLoaded();
        if (registrations.contains(registration) && registration.getStatus() == Registration.Status.ACTIVE) {
            registration.setStatus(Registration.Status.EXPIRED);
            try {
                save();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Cannot save the Bouteiller registrations", e);
            }
        }
    }
}
