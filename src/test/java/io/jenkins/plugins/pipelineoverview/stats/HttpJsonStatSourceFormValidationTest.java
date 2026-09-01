package io.jenkins.plugins.pipelineoverview.stats;

import hudson.util.FormValidation;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;

@WithJenkins
class HttpJsonStatSourceFormValidationTest {

    private static HttpJsonStatSource.DescriptorImpl descriptor(JenkinsRule j) {
        return j.jenkins.getDescriptorByType(HttpJsonStatSource.DescriptorImpl.class);
    }

    @Test
    void aPaddedUrlIsAccepted(JenkinsRule j) {
        assertEquals(FormValidation.Kind.OK,
                descriptor(j).doCheckUrl("  https://argocd.example.com/api/v1/applications  ").kind);
    }

    @Test
    void aBlankUrlIsRejected(JenkinsRule j) {
        assertEquals(FormValidation.Kind.ERROR, descriptor(j).doCheckUrl("   ").kind);
    }

    @Test
    void aNonHttpUrlIsRejected(JenkinsRule j) {
        assertEquals(FormValidation.Kind.ERROR,
                descriptor(j).doCheckUrl("file:///etc/passwd").kind);
    }
}
