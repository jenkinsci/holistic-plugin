package io.jenkins.plugins.pipelineoverview.stats;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.pipelineoverview.Messages;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

public class HttpJsonStatSource extends StatSource {
    private static final long serialVersionUID = 1L;

    static final int MIN_REFRESH_SECONDS = 15;
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private String url;
    private String pointer;
    private JsonPointerExtractor.Mode mode;
    private int refreshSeconds;
    private String credentialsId;

    @DataBoundConstructor
    public HttpJsonStatSource(String url) {
        this.url = url != null ? url.trim() : "";
        this.pointer = "";
        this.mode = JsonPointerExtractor.Mode.VALUE;
        this.refreshSeconds = 60;
    }

    public String getUrl()     { return url != null ? url : ""; }
    public String getPointer() { return pointer != null ? pointer : ""; }
    public String getCredentialsId() { return credentialsId != null ? credentialsId : ""; }

    public JsonPointerExtractor.Mode getMode() {
        return mode != null ? mode : JsonPointerExtractor.Mode.VALUE;
    }

    @Override
    public int getRefreshSeconds() {
        return Math.max(MIN_REFRESH_SECONDS, refreshSeconds);
    }

    @Override
    public boolean referencesCredentials() {
        return !getCredentialsId().isEmpty();
    }

    @DataBoundSetter
    public void setPointer(String pointer) { this.pointer = pointer != null ? pointer.trim() : ""; }

    @DataBoundSetter
    public void setMode(JsonPointerExtractor.Mode mode) { this.mode = mode; }

    @DataBoundSetter
    public void setRefreshSeconds(int refreshSeconds) { this.refreshSeconds = refreshSeconds; }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId != null ? credentialsId.trim() : "";
    }

    @Override
    public StatValue fetch() throws IOException {
        URI uri = validatedUri();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(READ_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        applyAuth(builder);
        HttpResponse<InputStream> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + safeTarget(uri), e);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " from " + safeTarget(uri));
            }
            String body = readCapped(in);
            return JsonPointerExtractor.extract(body, getPointer(), getMode(), System.currentTimeMillis());
        }
    }

    private void applyAuth(HttpRequest.Builder builder) throws IOException {
        String id = getCredentialsId();
        if (id.isEmpty()) return;
        StandardCredentials credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(id));
        if (credential instanceof StringCredentials secretText) {
            builder.header("Authorization", "Bearer " + secretText.getSecret().getPlainText());
            return;
        }
        if (credential instanceof StandardUsernamePasswordCredentials userPass) {
            String raw = userPass.getUsername() + ":" + userPass.getPassword().getPlainText();
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        throw new IOException("Credential '" + id + "' was not found, or is not a secret text "
                + "or username and password credential");
    }

    @Override
    public String cacheKey() {
        String identity = getUrl() + "|" + getPointer() + "|" + getMode() + "|" + getCredentialsId();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private URI validatedUri() throws IOException {
        if (!CustomStat.isSafeUrl(getUrl())) {
            throw new IOException("Stat URL must be an absolute http or https URL");
        }
        try {
            return new URI(getUrl());
        } catch (URISyntaxException e) {
            throw new IOException("Stat URL is malformed", e);
        }
    }

    private static String safeTarget(URI uri) {
        String host = uri.getHost();
        if (host == null) return "the configured host";
        return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
    }

    private static String readCapped(InputStream in) throws IOException {
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("Response exceeds the 1 MB limit");
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("Response body read timed out");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Extension
    @Symbol("httpJson")
    // Typed to StatSource so the form dropdown discovers every installed source type.
    public static class DescriptorImpl extends Descriptor<StatSource> {

        @Override
        public String getDisplayName() {
            return Messages.HttpJsonStatSource_DisplayName();
        }

        @POST
        public FormValidation doCheckUrl(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            String trimmed = value != null ? value.trim() : "";
            if (trimmed.isEmpty()) {
                return FormValidation.error(Messages.HttpJsonStatSource_UrlRequired());
            }
            if (!CustomStat.isSafeUrl(trimmed)) {
                return FormValidation.error(Messages.CustomStat_UrlSchemeInvalid());
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckPointer(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value == null || value.trim().isEmpty()) return FormValidation.ok();
            if (!value.trim().startsWith("/")) {
                return FormValidation.error(Messages.HttpJsonStatSource_PointerFormat());
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckRefreshSeconds(@QueryParameter int value) {
            Jenkins.get().checkPermission(Jenkins.READ);
            if (value < MIN_REFRESH_SECONDS) {
                return FormValidation.error(Messages.HttpJsonStatSource_RefreshTooLow());
            }
            return FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            StandardListBoxModel model = new StandardListBoxModel();
            Jenkins jenkins = Jenkins.get();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return model.includeCurrentValue(credentialsId);
            }
            return model
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, jenkins, StandardCredentials.class, List.of(),
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StringCredentials.class),
                                    CredentialsMatchers.instanceOf(
                                            StandardUsernamePasswordCredentials.class)))
                    .includeCurrentValue(credentialsId);
        }
    }
}
