package io.jenkins.plugins.bouteiller.push;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.bouteiller.BouteillerConfiguration;
import io.jenkins.plugins.bouteiller.Registration;
import io.jenkins.plugins.bouteiller.RegistrationStore;
import io.jenkins.plugins.bouteiller.util.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Sends the events to the subscribed devices, off the threads of the builds: selection of the
 * registrations, encryption per device, then the relay, with retries for a few minutes when it is unavailable.
 */
@Extension
public final class PushDispatcher {
    private static final Logger LOGGER = Logger.getLogger(PushDispatcher.class.getName());

    /** Delays between attempts: about 8 minutes in all, then the notification is given up. */
    private static final List<Duration> RETRY_DELAYS = List.of(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofMinutes(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5));

    private final ScheduledExecutorService executor;

    /** Builds the relay client from the URL and the proxy choice; replaced in tests. */
    private volatile Function<BouteillerConfiguration, Relay> relayFactory =
            configuration -> new RelayClient(configuration.getRelayUrl(), configuration.isUseProxy());

    private volatile List<Duration> retryDelays = RETRY_DELAYS;

    /** The relay client of the current configuration, kept to reuse its connections. */
    private Relay relay;

    /** The relay URL and proxy choice that {@link #relay} was built for: a cache key, not a secret. */
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private String relayKey;

    public PushDispatcher() {
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(
                4, new NamingThreadFactory(new DaemonThreadFactory(), "Bouteiller push"));
        pool.setRemoveOnCancelPolicy(true);
        this.executor = pool;
    }

    public static PushDispatcher get() {
        return Jenkins.get().getExtensionList(PushDispatcher.class).getInstance(PushDispatcher.class);
    }

    @Restricted(NoExternalUse.class)
    public void setRelayFactory(Function<BouteillerConfiguration, Relay> relayFactory) {
        synchronized (this) {
            this.relayFactory = relayFactory;
            this.relay = null;
            this.relayKey = null;
        }
    }

    @Restricted(NoExternalUse.class)
    public void setRetryDelays(List<Duration> retryDelays) {
        this.retryDelays = List.copyOf(retryDelays);
    }

    /**
     * Sends an event on {@code job} to every active registration subscribed to it whose user can read the job.
     * Returns at once.
     */
    public void publish(Item job, PushEvent event) {
        publish(job, () -> event);
    }

    /**
     * Same as {@link #publish(Item, PushEvent)}, the event being built off the calling thread (it may walk the flow
     * graph of a Pipeline); a {@code null} event sends nothing.
     */
    public void publish(Item job, Supplier<PushEvent> eventSupplier) {
        if (!BouteillerConfiguration.get().isPushEnabled()) {
            return;
        }
        executor.execute(() -> {
            PushEvent event;
            try {
                event = eventSupplier.get();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Cannot build an event of " + job.getFullName(), e);
                return;
            }
            if (event == null) {
                return;
            }
            for (Registration registration : RegistrationStore.get().all()) {
                if (registration.getStatus() == Registration.Status.ACTIVE
                        && registration.isSubscribed(event.jobFullName(), event.type())
                        && canRead(registration.getUserId(), job)) {
                    attempt(registration, event, 0);
                }
            }
        });
    }

    /** Sends the test notification of {@code POST /registrations/{id}/test} at once, without retry. */
    public Relay.Result sendTest(Registration registration, PushEvent event) {
        return send(registration, event);
    }

    private static boolean canRead(String userId, Item job) {
        User user = User.getById(userId, false);
        if (user == null) {
            return false;
        }
        try {
            Authentication authentication = user.impersonate2();
            try (var ignored = ACL.as2(ACL.SYSTEM2)) {
                return job.getACL().hasPermission2(authentication, Item.READ);
            }
        } catch (UsernameNotFoundException e) {
            LOGGER.fine(() -> "The user " + userId + " no longer exists: no notification");
            return false;
        }
    }

    private void attempt(Registration registration, PushEvent event, int attempt) {
        Relay.Result result = send(registration, event);
        switch (result.outcome()) {
            case DELIVERED -> LOGGER.fine(() -> "Event " + event.id() + " delivered to " + registration.getId());
            case EXPIRED -> {
                LOGGER.info(() -> "Registration " + registration.getId() + " of " + registration.getUserId()
                        + " expired: " + result.detail());
                RegistrationStore.get().expire(registration);
            }
            case DROPPED -> LOGGER.warning(() -> "Event " + event.id() + " for registration " + registration.getId()
                    + " given up: " + result.detail());
            case RETRY -> {
                List<Duration> delays = retryDelays;
                if (attempt < delays.size()) {
                    LOGGER.fine(() -> "Event " + event.id() + " retried: " + result.detail());
                    executor.schedule(
                            () -> attempt(registration, event, attempt + 1),
                            delays.get(attempt).toMillis(),
                            TimeUnit.MILLISECONDS);
                } else {
                    LOGGER.warning(() -> "Event " + event.id() + " for registration " + registration.getId()
                            + " given up after " + (attempt + 1) + " attempts: " + result.detail());
                }
            }
        }
    }

    private synchronized Relay relay() {
        BouteillerConfiguration configuration = BouteillerConfiguration.get();
        String key = configuration.getRelayUrl() + "|" + configuration.isUseProxy();
        if (relay == null || !key.equals(relayKey)) {
            relay = relayFactory.apply(configuration);
            relayKey = key;
        }
        return relay;
    }

    private Relay.Result send(Registration registration, PushEvent event) {
        String payload;
        try {
            byte[] json = Json.write(event.toJson(registration.getServerId())).getBytes(StandardCharsets.UTF_8);
            payload = EventCipher.encrypt(registration.encryptionKey(), json);
        } catch (GeneralSecurityException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot encrypt an event for registration " + registration.getId(), e);
            return new Relay.Result(Relay.Outcome.DROPPED, "encryption failed");
        }
        return relay().send(new Relay.Request(
                registration.deviceToken(),
                registration.getEnvironment(),
                registration.relayAccessToken(),
                payload,
                PushEvent.threadId(registration.getServerId(), event.jobFullName())));
    }
}
