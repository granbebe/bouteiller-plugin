package io.jenkins.plugins.bouteiller;

import hudson.Extension;
import hudson.util.FormValidation;
import io.jenkins.plugins.bouteiller.push.RelayClient;
import java.net.URI;
import java.net.URISyntaxException;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** Global configuration of the plugin, in <em>Manage Jenkins › System</em>. */
@Extension
@Symbol("bouteiller")
public final class BouteillerConfiguration extends GlobalConfiguration {
    /** The relay operated by the developer of Bouteiller. */
    public static final String DEFAULT_RELAY_URL = "https://bouteiller-relay.mannocci.fr";

    private boolean pushEnabled = true;
    private String relayUrl = DEFAULT_RELAY_URL;
    private boolean useProxy = true;

    public BouteillerConfiguration() {
        load();
    }

    public static BouteillerConfiguration get() {
        return GlobalConfiguration.all().getInstance(BouteillerConfiguration.class);
    }

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    @DataBoundSetter
    public void setPushEnabled(boolean pushEnabled) {
        this.pushEnabled = pushEnabled;
        save();
    }

    public String getRelayUrl() {
        return relayUrl == null || relayUrl.isBlank() ? DEFAULT_RELAY_URL : relayUrl;
    }

    @DataBoundSetter
    public void setRelayUrl(String relayUrl) {
        String trimmed = relayUrl == null ? "" : relayUrl.trim();
        this.relayUrl = trimmed.isEmpty() ? DEFAULT_RELAY_URL : trimmed.replaceAll("/+$", "");
        save();
    }

    /** Whether the relay is reached through the HTTP proxy of Jenkins (<em>Manage Jenkins › Plugins › Advanced</em>). */
    public boolean isUseProxy() {
        return useProxy;
    }

    @DataBoundSetter
    public void setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
        save();
    }

    public FormValidation doCheckRelayUrl(@QueryParameter String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
                return FormValidation.error(Messages.BouteillerConfiguration_InvalidRelayUrl());
            }
            if ("http".equals(uri.getScheme())) {
                return FormValidation.warning(Messages.BouteillerConfiguration_PlainHttp());
            }
            return FormValidation.ok();
        } catch (URISyntaxException e) {
            return FormValidation.error(Messages.BouteillerConfiguration_InvalidRelayUrl());
        }
    }

    /** "Test the relay" button: asks the relay for a challenge, the only call that needs no token. */
    @POST
    public FormValidation doTestRelay(@QueryParameter String relayUrl, @QueryParameter boolean useProxy) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String url = relayUrl == null || relayUrl.isBlank() ? DEFAULT_RELAY_URL : relayUrl.trim();
        try {
            RelayClient.checkReachable(url, useProxy);
            return FormValidation.ok(Messages.BouteillerConfiguration_RelayReachable());
        } catch (Exception e) {
            return FormValidation.error(Messages.BouteillerConfiguration_RelayUnreachable(e.getMessage()));
        }
    }
}
