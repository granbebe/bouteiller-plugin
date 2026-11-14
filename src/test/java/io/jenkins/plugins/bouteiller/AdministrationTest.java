package io.jenkins.plugins.bouteiller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bouteiller.util.Json;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AdministrationTest {
    @Test
    void globalConfigurationRoundTrips(JenkinsRule j) throws Exception {
        BouteillerConfiguration configuration = BouteillerConfiguration.get();
        configuration.setRelayUrl("https://relay.example.com/");
        configuration.setUseProxy(false);
        configuration.setPushEnabled(false);
        j.configRoundtrip();
        assertEquals("https://relay.example.com", configuration.getRelayUrl());
        assertFalse(configuration.isUseProxy());
        assertFalse(configuration.isPushEnabled());
        configuration.setRelayUrl("  ");
        assertEquals(BouteillerConfiguration.DEFAULT_RELAY_URL, configuration.getRelayUrl());
    }

    @Test
    void listsAndRevokesRegistrations(JenkinsRule j) throws Exception {
        RegistrationStore.get().update("alice", "device", previous -> Registration.fromJson("device", "alice",
                Json.parseObject(TestSupport.registrationBody("ab".repeat(32), null)), previous, 0));
        JenkinsRule.WebClient client = j.createWebClient();
        HtmlPage page = client.goTo("manage/bouteiller-registrations/");
        String text = page.asNormalizedText();
        assertTrue(text.contains("alice"), text);
        assertTrue(text.contains("…" + "ab".repeat(4)), text);
        assertFalse(page.asXml().contains("ab".repeat(32)), "the page never shows the whole device token");

        HtmlForm form = page.getForms().stream()
                .filter(candidate -> candidate.getActionAttribute().equals("revoke"))
                .findFirst()
                .orElseThrow();
        j.submit(form);
        assertNull(RegistrationStore.get().find("alice", "device"));
    }
}
