package io.jenkins.plugins.bouteiller;

import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** <em>Manage Jenkins › Bouteiller devices</em>: the registrations of every user, with revocation. */
@Extension
public final class RegistrationsManagementLink extends ManagementLink {
    @Override
    public String getIconFileName() {
        return "symbol-notifications";
    }

    @Override
    public String getDisplayName() {
        return Messages.RegistrationsManagementLink_DisplayName();
    }

    @Override
    public String getDescription() {
        return Messages.RegistrationsManagementLink_Description();
    }

    @Override
    public String getUrlName() {
        return "bouteiller-registrations";
    }

    @Override
    public Permission getRequiredPermission() {
        return Jenkins.ADMINISTER;
    }

    @Override
    public Category getCategory() {
        return Category.SECURITY;
    }

    public List<Registration> getRegistrations() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return RegistrationStore.get().all();
    }

    /** Revokes a registration: the device no longer receives anything until the app registers again. */
    @POST
    public HttpResponse doRevoke(@QueryParameter String userId, @QueryParameter String id) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        RegistrationStore.get().remove(userId, id);
        return HttpResponses.redirectToDot();
    }
}
