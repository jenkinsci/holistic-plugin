package io.jenkins.plugins.pipelineoverview.stats;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.sun.net.httpserver.HttpServer;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class HttpJsonStatSourceCredentialsTest {

    private HttpServer server;
    private String base;
    private final StringBuilder authSeen = new StringBuilder();

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authSeen.setLength(0);
            authSeen.append(auth != null ? auth : "");
            byte[] bytes = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpJsonStatSource source(String credentialsId) {
        HttpJsonStatSource s = new HttpJsonStatSource(base + "/x");
        s.setPointer("/n");
        s.setCredentialsId(credentialsId);
        return s;
    }

    @Test
    void secretTextBecomesABearerToken(JenkinsRule j) throws Exception {
        startServer();
        try {
            SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global())
                    .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "tok", "desc",
                            Secret.fromString("s3cr3t")));
            source("tok").fetch();
            assertEquals("Bearer s3cr3t", authSeen.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usernamePasswordBecomesBasicAuth(JenkinsRule j) throws Exception {
        startServer();
        try {
            SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global())
                    .add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "up", "desc",
                            "admin", "admin"));
            source("up").fetch();
            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, authSeen.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noCredentialMeansNoAuthorizationHeader(JenkinsRule j) throws Exception {
        startServer();
        try {
            source("").fetch();
            assertEquals("", authSeen.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownCredentialIdIsAnError(JenkinsRule j) throws Exception {
        startServer();
        try {
            IOException e = assertThrows(IOException.class, () -> source("does-not-exist").fetch());
            assertTrue(e.getMessage().contains("does-not-exist"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void credentialsDropdownListsMatchingCredentials(JenkinsRule j) {
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global())
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "tok2", "desc",
                        Secret.fromString("s3cr3t")));
        HttpJsonStatSource.DescriptorImpl d = j.jenkins
                .getDescriptorByType(HttpJsonStatSource.DescriptorImpl.class);
        assertTrue(d.doFillCredentialsIdItems("").stream()
                .anyMatch(o -> "tok2".equals(o.value)));
    }

    @Test
    void nonAdministratorDoesNotSeeTheCredentialListButKeepsCurrentValue(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("alice"));
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().get(Domain.global())
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "withheld", "desc",
                        Secret.fromString("s3cr3t")));
        HttpJsonStatSource.DescriptorImpl d = j.jenkins
                .getDescriptorByType(HttpJsonStatSource.DescriptorImpl.class);
        ListBoxModel options;
        try (ACLContext ctx = ACL.as(User.getById("alice", true))) {
            options = d.doFillCredentialsIdItems("previously-selected-id");
        }
        assertFalse(options.stream().anyMatch(o -> "withheld".equals(o.value)));
        assertTrue(options.stream().anyMatch(o -> "previously-selected-id".equals(o.value)));
    }
}
