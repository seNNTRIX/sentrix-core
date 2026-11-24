package com.sentrix.core.analysis;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.sentrix.core.analysis.AnalysisProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisModelClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private AnalysisModelClient client;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger redirected = new AtomicInteger();
    private final AtomicLong bodyDelay = new AtomicLong();
    private final Request request = new Request(CONTRACT_VERSION, UUID.randomUUID(), 7, "sentrix",
            Instant.parse("2026-11-22T01:00:00Z"), Instant.parse("2026-11-22T01:00:30Z"), "features-v1", "model-v1");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/analyses", exchange -> {
            try (exchange) {
                received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                if (status.get() == 302) exchange.getResponseHeaders().add("Location", "/redirect-target");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                Thread.sleep(bodyDelay.get());
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        server.createContext("/redirect-target", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        client = new AnalysisModelClient(settings(true, 1), mapper);
        response.set(valid().toString());
    }

    @AfterEach
    void stop() { server.stop(0); executor.shutdownNow(); }

    private AnalysisModelSettings settings(boolean enabled, int timeout) {
        return new AnalysisModelSettings(enabled, "http://127.0.0.1:" + server.getAddress().getPort(), "model-v1", "features-v1", timeout);
    }

    private ObjectNode valid() {
        var root = mapper.createObjectNode();
        root.put("contractVersion", request.contractVersion()); root.put("jobId", request.jobId().toString());
        root.put("environmentId", request.environmentId()); root.put("namespace", request.namespace());
        root.put("windowStart", request.windowStart().toString()); root.put("windowEnd", request.windowEnd().toString());
        root.put("featureSchemaVersion", request.featureSchemaVersion()); root.put("modelVersion", request.modelVersion());
        root.put("decision", "NORMAL"); root.put("score", 2.5); root.putArray("candidates"); root.putNull("abstainReason");
        return root;
    }

    private ObjectNode candidate(int rank, String service) {
        var candidate = mapper.createObjectNode();
        candidate.put("rank", rank); candidate.put("kind", "NODE"); candidate.put("service", service);
        candidate.putNull("source"); candidate.putNull("target"); candidate.put("faultType", "cpu_saturation");
        candidate.put("score", 3.4); candidate.putArray("evidence").add("CPU usage increased");
        return candidate;
    }

    private void failure(String code) { assertEquals(code, assertThrows(AnalysisModelClient.AnalysisFailure.class, () -> client.analyze(request)).code()); }

    @Test
    void sendsBoundedWindowMetadataAndPreservesNonProbabilityScores() {
        Response result = client.analyze(request);
        assertEquals(Decision.NORMAL, result.decision()); assertEquals(2.5, result.score());
        var sent = mapper.readTree(received.get());
        assertEquals(8, sent.size()); assertEquals(request.jobId().toString(), sent.path("jobId").asText());
        assertEquals(request.windowStart().toString(), sent.path("windowStart").asText());
        assertFalse(sent.has("sources")); assertFalse(sent.has("credentials"));
    }

    @Test
    void acceptsNodeEdgeCandidatesAndAbstention() {
        var root = valid(); root.put("decision", "ANOMALY");
        var edge = candidate(1, "frontend"); edge.put("kind", "EDGE"); edge.putNull("service");
        edge.put("source", "frontend"); edge.put("target", "productcatalogservice");
        root.withArray("candidates").add(edge).add(candidate(2, "productcatalogservice"));
        response.set(root.toString());
        var result = client.analyze(request);
        assertEquals(CandidateKind.EDGE, result.candidates().getFirst().kind()); assertEquals(3.4, result.candidates().getFirst().score());
        root = valid(); root.put("decision", "ABSTAIN"); root.putNull("score"); root.put("abstainReason", "INSUFFICIENT_SAMPLES");
        response.set(root.toString()); assertEquals(Decision.ABSTAIN, client.analyze(request).decision());
    }

    @Test
    void rejectsMismatchedIdentityWindowAndVersions() {
        for (String key : List.of("contractVersion", "jobId", "namespace", "windowStart", "windowEnd", "featureSchemaVersion", "modelVersion")) {
            var root = valid();
            root.put(key, switch (key) { case "jobId" -> UUID.randomUUID().toString(); case "windowStart", "windowEnd" -> "2026-11-22T02:00:00Z"; default -> "different"; });
            response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
        }
        var root = valid(); root.put("environmentId", 8); response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
        root = valid(); root.put("environmentId", "7"); response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
    }

    @Test
    void rejectsMalformedNonfiniteOrAmbiguousDecisions() {
        for (String body : List.of("{", "[]", valid() + " {}", valid().toString().replace("2.5", "1e999"), valid().toString().replace("2.5", "\"NaN\""))) {
            response.set(body); failure("INVALID_MODEL_RESPONSE");
        }
        var root = valid(); root.put("decision", "ANOMALY"); response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
        root = valid(); root.put("decision", "ABSTAIN"); response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
        root = valid(); root.withArray("candidates").add(candidate(1, "frontend")); response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
    }

    @Test
    void rejectsDuplicateTargetsWrongRanksAndUnboundedEvidence() {
        var root = valid(); root.put("decision", "ANOMALY"); root.withArray("candidates").add(candidate(1, "frontend")).add(candidate(2, "frontend"));
        response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
        root = valid(); root.put("decision", "ANOMALY"); root.withArray("candidates").add(candidate(2, "frontend"));
        response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
        root = valid(); root.put("decision", "ANOMALY"); var item = candidate(1, "frontend"); item.withArray("evidence").add("x".repeat(1001)); root.withArray("candidates").add(item);
        response.set(root.toString()); failure("INVALID_MODEL_RESPONSE");
    }

    @Test
    void doesNotFollowRedirectsOrExposeServerErrors() {
        status.set(302); failure("MODEL_REDIRECT_REJECTED"); assertEquals(0, redirected.get());
        status.set(500); response.set("private upstream details");
        var error = assertThrows(AnalysisModelClient.AnalysisFailure.class, () -> client.analyze(request));
        assertEquals("MODEL_HTTP_ERROR", error.getMessage()); assertNull(error.getCause());
    }

    @Test
    void limitsWholeBodyTimeAndSize() {
        bodyDelay.set(4000);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> failure("MODEL_TIMEOUT"));
        bodyDelay.set(0); response.set("x".repeat(1024 * 1024 + 1)); failure("MODEL_RESPONSE_TOO_LARGE");
    }

    @Test
    void logModelMayUseAnIndependentServerOrShareTheAnalysisServer() {
        var separate = new AnalysisModelSettings(true, "http://127.0.0.1:8000", "v1", "v1", 10,
                "http://127.0.0.1:18002/private");
        assertEquals("http://127.0.0.1:8000/v1/analyses", separate.endpoint().toString());
        assertEquals("http://127.0.0.1:18002/private/v1/log-semantics", separate.logSemanticsEndpoint().toString());
        var shared = new AnalysisModelSettings(true, "http://localhost:8000/", "v1", "v1", 10);
        assertEquals("http://localhost:8000/v1/log-semantics", shared.logSemanticsEndpoint().toString());
        assertThrows(IllegalArgumentException.class, () -> new AnalysisModelSettings(true, "http://localhost", "v1", "v1",
                10, "https://untrusted.invalid/path?access=token"));
    }

    @Test
    void disabledOrInvalidRequestNeverCallsServer() {
        var disabled = new AnalysisModelClient(settings(false, 1), mapper);
        assertEquals("MODEL_UNAVAILABLE", assertThrows(AnalysisModelClient.AnalysisFailure.class, () -> disabled.analyze(request)).code());
        var invalid = new Request(CONTRACT_VERSION, request.jobId(), 7, "sentrix", request.windowStart(), request.windowStart().plusSeconds(60), "features-v1", "model-v1");
        assertEquals("INVALID_ANALYSIS_REQUEST", assertThrows(AnalysisModelClient.AnalysisFailure.class, () -> client.analyze(invalid)).code());
        var misaligned = new Request(CONTRACT_VERSION, request.jobId(), 7, "sentrix", request.windowStart().plusSeconds(1), request.windowEnd().plusSeconds(1), "features-v1", "model-v1");
        assertEquals("INVALID_ANALYSIS_REQUEST", assertThrows(AnalysisModelClient.AnalysisFailure.class, () -> client.analyze(misaligned)).code());
        assertNull(received.get());
    }

    @Test
    void validatesOperatorConfigurationWithoutEchoingValues() {
        assertFalse(new AnalysisModelSettings(false, "", "", "", 10).enabled());
        for (String url : List.of("http://user:secret@localhost", "http://localhost?token=secret", "http://localhost#secret", "file:///tmp/model", "http://localhost/../secret", "http://localhost/%2e%2e/secret", "http://localhost:0", "http://localhost:65536")) {
            assertEquals("Invalid analysis server configuration", assertThrows(IllegalArgumentException.class,
                    () -> new AnalysisModelSettings(true, url, "v1", "v1", 10)).getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new AnalysisModelSettings(true, "", "v1", "v1", 10));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisModelSettings(true, "http://localhost", "", "v1", 10));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisModelSettings(true, "http://localhost", "v1", "bad version", 10));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisModelSettings(true, "http://localhost", "v1", "v1", 61));
    }
}
