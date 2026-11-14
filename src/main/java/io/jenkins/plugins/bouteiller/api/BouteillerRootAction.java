package io.jenkins.plugins.bouteiller.api;

import hudson.Extension;
import hudson.model.RootAction;
import jenkins.security.stapler.StaplerAccessibleType;

/**
 * Root of the REST API of the app, {@code /bouteiller/api/v1}. Not shown in the menus; standard Jenkins
 * authentication (the app sends a user name and an API token) and Overall/Read.
 */
@Extension
public final class BouteillerRootAction implements RootAction {
    public static final String URL_NAME = "bouteiller";

    private final ApiVersions api = new ApiVersions();

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    public ApiVersions getApi() {
        return api;
    }

    /** {@code /bouteiller/api}: one object per version of the API. */
    @StaplerAccessibleType
    public static final class ApiVersions {
        private final ApiV1 v1 = new ApiV1();

        public ApiV1 getV1() {
            return v1;
        }
    }
}
