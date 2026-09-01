package io.jenkins.plugins.pipelineoverview;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import hudson.model.View;
import hudson.util.Secret;
import io.jenkins.plugins.pipelineoverview.stats.CustomStat;
import io.jenkins.plugins.pipelineoverview.stats.HttpJsonStatSource;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class CustomStatsConfigureSecurityTest {

    private static final String VIEW = "dash";
    private static final String ATTACKER_URL = "http://attacker.example.com/collect";

    private PipelineOverviewDashboard secure(JenkinsRule j) throws Exception {
        PipelineOverviewDashboard view = new PipelineOverviewDashboard(VIEW);
        j.jenkins.addView(view);
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global())
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "argocd-readonly", "desc",
                        Secret.fromString("s3cr3t")));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, View.READ, View.CONFIGURE).everywhere().to("viewconfigurer")
                .grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        return view;
    }

    private static JSONObject configuration(String credentialsId) {
        JSONObject source = new JSONObject();
        source.put("$class", HttpJsonStatSource.class.getName());
        source.put("url", ATTACKER_URL);
        source.put("credentialsId", credentialsId);
        source.put("pointer", "/n");
        source.put("mode", "VALUE");
        source.put("refreshSeconds", 60);

        JSONObject stat = new JSONObject();
        stat.put("label", "Preview Envs");
        stat.put("source", source);

        JSONObject form = new JSONObject();
        form.put("name", VIEW);
        form.put("description", "");
        form.put("refreshIntervalSeconds", 30);
        form.put("historyDays", 30);
        form.put("customStats", stat);
        return form;
    }

    private Page save(JenkinsRule j, String user, JSONObject form) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login(user);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        WebRequest request = new WebRequest(
                new URL(j.getURL(), "view/" + VIEW + "/configSubmit"), HttpMethod.POST);
        request.setRequestParameters(List.of(
                new NameValuePair("name", VIEW),
                new NameValuePair("description", ""),
                new NameValuePair("json", form.toString())));
        return wc.getPage(wc.addCrumb(request));
    }

    private static HttpJsonStatSource onlySource(PipelineOverviewDashboard view) {
        assertEquals(1, view.getCustomStats().size());
        CustomStat stat = view.getCustomStats().get(0);
        return assertInstanceOf(HttpJsonStatSource.class, stat.getSource());
    }

    @Test
    void viewConfigureWithoutAdministerCannotSaveACredentialBackedStat(JenkinsRule j)
            throws Exception {
        PipelineOverviewDashboard view = secure(j);

        Page response = save(j, "viewconfigurer", configuration("argocd-readonly"));

        assertEquals(400, response.getWebResponse().getStatusCode(),
                "a non administrator must not be able to save a credential backed stat");
        assertTrue(view.getCustomStats().isEmpty(),
                "the rejected configuration must not have been applied: "
                        + view.getCustomStats());
    }

    @Test
    void administratorCanSaveTheSameCredentialBackedStat(JenkinsRule j) throws Exception {
        PipelineOverviewDashboard view = secure(j);

        Page response = save(j, "admin", configuration("argocd-readonly"));

        assertEquals(200, response.getWebResponse().getStatusCode());
        HttpJsonStatSource source = onlySource(view);
        assertEquals("argocd-readonly", source.getCredentialsId());
        assertEquals(ATTACKER_URL, source.getUrl());
    }

    @Test
    void viewConfigureWithoutAdministerCanStillSaveAStatWithoutCredentials(JenkinsRule j)
            throws Exception {
        PipelineOverviewDashboard view = secure(j);

        Page response = save(j, "viewconfigurer", configuration(""));

        assertEquals(200, response.getWebResponse().getStatusCode());
        HttpJsonStatSource source = onlySource(view);
        assertEquals("", source.getCredentialsId());
        assertEquals(ATTACKER_URL, source.getUrl());
    }
}
