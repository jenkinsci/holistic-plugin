package io.jenkins.plugins.pipelineoverview.stats;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpJsonStatSourceTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private HttpJsonStatSource source(String path, String pointer,
                                      JsonPointerExtractor.Mode mode) {
        HttpJsonStatSource s = new HttpJsonStatSource(base + path);
        s.setPointer(pointer);
        s.setMode(mode);
        return s;
    }

    @Test
    void fetchesAndExtractsValue() throws IOException {
        respond("/gh", 200, "{\"total_count\":4}");
        StatValue v = source("/gh", "/total_count", JsonPointerExtractor.Mode.VALUE).fetch();
        assertEquals(4.0, v.getNumeric());
        assertTrue(v.getFetchedAt() > 0);
    }

    @Test
    void countsArrayItems() throws IOException {
        respond("/argo", 200, "{\"items\":[{},{},{},{},{}]}");
        StatValue v = source("/argo", "/items", JsonPointerExtractor.Mode.COUNT).fetch();
        assertEquals(5.0, v.getNumeric());
    }

    @Test
    void sendsAcceptJsonHeader() throws IOException {
        StringBuilder seen = new StringBuilder();
        server.createContext("/hdr", exchange -> {
            seen.append(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] bytes = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        source("/hdr", "/a", JsonPointerExtractor.Mode.VALUE).fetch();
        assertEquals("application/json", seen.toString());
    }

    @Test
    void nonSuccessStatusIsAnError() {
        respond("/boom", 500, "{\"error\":\"nope\"}");
        IOException e = assertThrows(IOException.class,
                () -> source("/boom", "/error", JsonPointerExtractor.Mode.VALUE).fetch());
        assertTrue(e.getMessage().contains("500"));
    }

    @Test
    void errorMessageDoesNotLeakQueryString() {
        respond("/secret", 500, "{}");
        HttpJsonStatSource s = new HttpJsonStatSource(base + "/secret?token=hunter2");
        s.setPointer("/a");
        IOException e = assertThrows(IOException.class, s::fetch);
        assertFalse(e.getMessage().contains("hunter2"));
    }

    @Test
    void errorMessageDoesNotLeakUrlUserInfo() {
        respond("/creds", 500, "{}");
        HttpJsonStatSource s = new HttpJsonStatSource(
                "http://alice:hunter2@127.0.0.1:" + server.getAddress().getPort() + "/creds");
        s.setPointer("/a");
        IOException e = assertThrows(IOException.class, s::fetch);
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
        assertFalse(e.getMessage().contains("alice"), e.getMessage());
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
    }

    @Test
    void oversizedBodyIsAnError() {
        StringBuilder big = new StringBuilder("{\"pad\":\"");
        big.append("x".repeat(1_200_000));
        big.append("\",\"a\":1}");
        respond("/big", 200, big.toString());
        assertThrows(IOException.class,
                () -> source("/big", "/a", JsonPointerExtractor.Mode.VALUE).fetch());
    }

    @Test
    void nonHttpSchemeIsRejectedBeforeAnyRequest() {
        HttpJsonStatSource s = new HttpJsonStatSource("file:///etc/passwd");
        s.setPointer("/a");
        assertThrows(IOException.class, s::fetch);
    }

    @Test
    void redirectIsNotFollowed() {
        server.createContext("/redir", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/gh");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        respond("/gh", 200, "{\"total_count\":4}");
        assertThrows(IOException.class,
                () -> source("/redir", "/total_count", JsonPointerExtractor.Mode.VALUE).fetch());
    }

    @Test
    void refreshSecondsHasAFloor() {
        HttpJsonStatSource s = new HttpJsonStatSource(base + "/gh");
        s.setRefreshSeconds(2);
        assertEquals(15, s.getRefreshSeconds());
    }

    @Test
    void cacheKeyIsStableAndHidesTheUrl() {
        HttpJsonStatSource a = source("/gh", "/total_count", JsonPointerExtractor.Mode.VALUE);
        HttpJsonStatSource b = source("/gh", "/total_count", JsonPointerExtractor.Mode.VALUE);
        HttpJsonStatSource c = source("/gh", "/other", JsonPointerExtractor.Mode.VALUE);
        assertEquals(a.cacheKey(), b.cacheKey());
        assertNotEquals(a.cacheKey(), c.cacheKey());
        assertFalse(a.cacheKey().contains("127.0.0.1"));
    }

    @Test
    void slowBodyDripDoesNotHangPastReadTimeout() {
        server.createContext("/drip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 15; i++) {
                    os.write('{');
                    os.flush();
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        HttpJsonStatSource s = source("/drip", "/a", JsonPointerExtractor.Mode.VALUE);
        assertTimeoutPreemptively(Duration.ofSeconds(13), () -> {
            IOException e = assertThrows(IOException.class, s::fetch);
            assertTrue(e.getMessage().contains("timed out"));
        });
    }
}
