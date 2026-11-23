package com.sentrix.core.incident;

import com.sentrix.core.environment.Environment;
import com.sentrix.core.environment.EnvironmentRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "sentrix.analysis.worker-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisExecutionTests {
    static final ObjectMapper json = new ObjectMapper();
    static final AtomicInteger calls = new AtomicInteger();
    static final AtomicInteger responseStatus = new AtomicInteger(200);
    static final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    static final AtomicReference<String> responseOverride = new AtomicReference<>();
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;
    static final ExecutorService httpThreads = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer model = startModel();
    static final String modelUrl = "http://127.0.0.1:" + model.getAddress().getPort();
    static final String WINDOW_END = "2026-01-01T00:00:30Z";

    @Autowired MockMvc mvc;
    @Autowired EnvironmentRepository environments;
    @Autowired JdbcTemplate db;
    @Autowired AnalysisJobRunner runner;
    @Autowired AnalysisJobs jobs;
    Long environmentId;
    Long otherId;
    String base;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sentrix.analysis.enabled", () -> true);
        registry.add("sentrix.analysis.base-url", () -> modelUrl);
        registry.add("sentrix.analysis.model-version", () -> "test-model");
        registry.add("sentrix.analysis.feature-schema-version", () -> "test-features");
        registry.add("sentrix.analysis.timeout-seconds", () -> 5);
    }

    static String successfulResponse(JsonNode request) {
        var result = json.createObjectNode();
        request.properties().forEach(entry -> result.set(entry.getKey(), entry.getValue()));
        result.put("decision", "NORMAL");
        result.put("score", 0.1);
        result.putArray("candidates");
        result.putNull("abstainReason");
        return result.toString();
    }

    static HttpServer startModel() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(httpThreads);
            server.createContext("/", exchange -> {
                try (exchange) {
                    calls.incrementAndGet();
                    JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
                    lastRequest.set(request);
                    var signal = entered;
                    var gate = release;
                    if (signal != null) signal.countDown();
                    if (gate != null && !gate.await(10, TimeUnit.SECONDS)) return;
                    String override = responseOverride.get();
                    byte[] body = (override == null ? successfulResponse(request) : override).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(responseStatus.get(), body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @BeforeEach
    void setUp() {
        calls.set(0);
        responseStatus.set(200);
        responseOverride.set(null);
        lastRequest.set(null);
        entered = null;
        release = null;
        environmentId = environments.saveAndFlush(new Environment("execution-" + UUID.randomUUID(), "sentrix")).getId();
        otherId = environments.saveAndFlush(new Environment("other-execution-" + UUID.randomUUID(), "other")).getId();
        base = "/api/v1/environments/" + environmentId + "/incidents";
    }

    @AfterEach
    void clean() {
        if (release != null) release.countDown();
        db.update("DELETE FROM analysis_jobs WHERE automatic_environment_id=?", environmentId);
        db.update("DELETE FROM monitoring_settings WHERE environment_id=?", environmentId);
        db.update("DELETE FROM analysis_jobs WHERE incident_id IN (SELECT id FROM incidents WHERE environment_id=?)", environmentId);
        db.update("DELETE FROM incidents WHERE environment_id=?", environmentId);
        environments.deleteById(environmentId);
        environments.deleteById(otherId);
    }

    @AfterAll
    static void stopModel() {
        model.stop(0);
        httpThreads.shutdownNow();
    }

    String incident() throws Exception {
        var input = json.createObjectNode()
                .put("requestKey", UUID.randomUUID().toString())
                .put("service", "frontend")
                .put("title", "분석 실행 검증")
                .put("description", "테스트용 수동 기록")
                .put("occurredAt", "2026-01-01T00:00:05Z");
        return read(mvc.perform(post(base).contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
    }

    String job(String incidentId, String key, String windowEnd) throws Exception {
        var input = json.createObjectNode().put("requestKey", key);
        if (windowEnd != null) input.put("windowEnd", windowEnd);
        return read(mvc.perform(post(path(incidentId)).contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();
    }

    String path(String incidentId) { return base + "/" + incidentId + "/rca-jobs"; }
    static JsonNode read(String value) { return json.readTree(value); }
    String state(String id) { return db.queryForObject("SELECT status FROM analysis_jobs WHERE id=?", String.class, id); }

    @Test
    void automaticAlertsRetainEvidenceAndCloseOnlyOnNormal() throws Exception {
        var first = Instant.parse("2026-01-01T00:02:00Z");
        db.update("INSERT INTO monitoring_settings(environment_id,enabled,interval_seconds,updated_at,next_run_at) VALUES(?,true,60,?,?)",
                environmentId, Timestamp.from(first), Timestamp.from(first));
        String record = null;
        String latestAnomalyJob = null;
        for (var decision : java.util.List.of("ANOMALY", "ANOMALY", "ABSTAIN", "NORMAL")) {
            jobs.enqueueDue(first);
            var req = read(db.queryForObject("SELECT request_json FROM analysis_jobs WHERE automatic_environment_id=? AND status='QUEUED'",
                    String.class, environmentId));
            var body = json.readTree(successfulResponse(req)).deepCopy();
            var response = (tools.jackson.databind.node.ObjectNode) body;
            response.put("decision", decision);
            if (decision.equals("ANOMALY")) {
                response.putArray("candidates").addObject().put("rank",1).put("kind","EDGE").putNull("service")
                        .put("source","frontend").put("target","productcatalogservice").put("faultType","호출 경로")
                        .put("score",0.8).putArray("evidence").add("호출 지연 증가");
            } else if (decision.equals("ABSTAIN")) {
                response.put("abstainReason","INSUFFICIENT_METRIC_SAMPLES");
                response.putNull("score");
            }
            responseOverride.set(response.toString());
            runner.runNext();
            assertThat(db.queryForObject("SELECT reason FROM analysis_jobs WHERE id=?", String.class, req.path("jobId").asText())).isEqualTo("COMPLETED");
            var page = read(mvc.perform(get(base)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(page.path("totalElements").asInt()).isEqualTo(1);
            var item = page.path("items").get(0);
            if (record == null) record = item.path("id").asText();
            assertThat(item.path("id").asText()).isEqualTo(record);
            assertThat(item.path("source").asText()).isEqualTo("AUTOMATIC");
            assertThat(item.path("status").asText()).isEqualTo(decision.equals("NORMAL") ? "RESOLVED" : "OPEN");
            mvc.perform(get("/api/v1/environments/"+environmentId+"/overview")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.openIncidents").value(decision.equals("NORMAL") ? 0 : 1))
                    .andExpect(jsonPath("$.recentWindows[0].decision").value(decision));
            if (decision.equals("ANOMALY")) {
                mvc.perform(get("/api/v1/environments/"+environmentId+"/overview?limit=1"))
                        .andExpect(jsonPath("$.recentWindows.length()").value(1))
                        .andExpect(jsonPath("$.recentWindows[0].locations[0].rank").value(1));
            }
            mvc.perform(get("/api/v1/environments/"+otherId+"/overview")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.openIncidents").value(0)).andExpect(jsonPath("$.recentWindows").isEmpty());
            if (decision.equals("ANOMALY")) latestAnomalyJob = req.path("jobId").asText();
            mvc.perform(get(base + "/" + record + "/rca")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.jobId").value(latestAnomalyJob))
                    .andExpect(jsonPath("$.result.candidates[0].kind").value("EDGE"));
            if (!decision.equals("ABSTAIN")) {
                String jobId = req.path("jobId").asText();
                mvc.perform(get(path(record)+"/"+jobId+"/result")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.decision").value(decision));
            }
            first = first.plusSeconds(60);
        }
        mvc.perform(get(path(record))).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(patch(base+"/"+record).contentType("application/json").content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/environments/"+otherId+"/incidents/"+record)).andExpect(status().isNotFound());
    }

    @Test
    void latestWindowIsPinnedByServerAndRetryReturnsTheSameJob() throws Exception {
        var path = path(incident());
        var input = json.createObjectNode().put("latest", true).put("requestKey", UUID.randomUUID().toString());
        long before = Instant.now().getEpochSecond();
        var result = read(mvc.perform(post(path).contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var end = Instant.parse(result.path("windowEnd").asText());
        assertThat(end.getEpochSecond()).isBetween(before-9, Instant.now().getEpochSecond());
        assertThat(end.getEpochSecond() % 10).isZero();
        assertThat(Instant.parse(result.path("windowStart").asText())).isEqualTo(end.minusSeconds(30));
        mvc.perform(post(path).contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(result.path("id").asText()));
        input.put("windowEnd", end.toString());
        mvc.perform(post(path).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
    }

    @Test
    void configuredModelDoesNotClaimAutomaticMonitoring() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/environments/"+environmentId+"/monitoring")
                .contentType("application/json").content("{\"enabled\":true,\"intervalSeconds\":60}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modelStatus").value("CONFIGURED"))
                .andExpect(jsonPath("$.executionStatus").value("BLOCKED_AUTOMATIC_ANALYSIS_UNAVAILABLE"));
        db.update("DELETE FROM monitoring_settings WHERE environment_id=?",environmentId);
    }

    @Test
    void latePollKeepsTheOriginalObservationGrid() {
        var due = Instant.parse("2026-01-01T00:02:00Z");
        db.update("INSERT INTO monitoring_settings(environment_id,enabled,interval_seconds,updated_at,next_run_at) VALUES(?,true,30,?,?)",
                environmentId,Timestamp.from(due),Timestamp.from(due));
        try {
            jobs.enqueueDue(due.plusSeconds(14));
            assertThat(db.queryForObject("SELECT window_end FROM analysis_jobs WHERE automatic_environment_id=?",Timestamp.class,environmentId).toInstant()).isEqualTo(due.minusSeconds(30));
            assertThat(db.queryForObject("SELECT next_run_at FROM monitoring_settings WHERE environment_id=?",Timestamp.class,environmentId).toInstant()).isEqualTo(due.plusSeconds(30));
        } finally {
            db.update("DELETE FROM analysis_jobs WHERE automatic_environment_id=?",environmentId);
            db.update("DELETE FROM monitoring_settings WHERE environment_id=?",environmentId);
        }
    }

    @Test
    void automaticWindowsAreIndependentIsolatedAndStopOnDisable() throws Exception {
        String url="/api/v1/environments/"+environmentId;
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url+"/monitoring")
                    .contentType("application/json").content("{\"enabled\":true,\"intervalSeconds\":30}"))
                    .andExpect(status().isOk());
            var now=Instant.now();
            jobs.enqueueDue(now);
            jobs.enqueueDue(now);
            runner.runNext();
            mvc.perform(get(url+"/anomaly-scores"))
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].score").value(0.1))
                    .andExpect(jsonPath("$[0].queueWaitMs").isNumber()).andExpect(jsonPath("$[0].executionMs").isNumber());
            mvc.perform(get("/api/v1/environments/"+otherId+"/anomaly-scores")).andExpect(jsonPath("$.length()").value(0));
            jobs.enqueueDue(now.plusSeconds(1200));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url+"/monitoring")
                    .contentType("application/json").content("{\"enabled\":false,\"intervalSeconds\":30}"))
                    .andExpect(jsonPath("$.lastSuccessfulAt").isString());
            jobs.enqueueDue(now.plusSeconds(1500));
            mvc.perform(get(url+"/anomaly-scores")).andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].reason").value("MONITORING_DISABLED"));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM incidents WHERE environment_id=?",Integer.class,environmentId)).isZero();
        } finally {
            db.update("DELETE FROM analysis_jobs WHERE automatic_environment_id=?",environmentId);
            db.update("DELETE FROM monitoring_settings WHERE environment_id=?",environmentId);
        }
    }

    @Test
    void queuesFixedInputAndSavesValidatedResultWithEnvironmentIsolation() throws Exception {
        String incident = incident();
        String key = UUID.randomUUID().toString();
        String job = job(incident, key, WINDOW_END);
        assertThat(state(job)).isEqualTo("QUEUED");
        mvc.perform(get(path(incident) + "/" + job + "/result")).andExpect(status().isConflict());
        assertThat(job(incident, key, null)).isEqualTo(job);
        mvc.perform(post(path(incident)).contentType("application/json").content(
                "{\"requestKey\":\"" + key + "\",\"windowEnd\":\"2026-01-01T00:00:40Z\"}"))
                .andExpect(status().isConflict());

        runner.runNext();

        assertThat(state(job)).isEqualTo("SUCCEEDED");
        assertThat(calls.get()).isEqualTo(1);
        JsonNode request = lastRequest.get();
        assertThat(request.path("jobId").asText()).isEqualTo(job);
        assertThat(request.path("environmentId").asLong()).isEqualTo(environmentId);
        assertThat(request.path("namespace").asText()).isEqualTo("sentrix");
        assertThat(request.path("windowStart").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(request.path("windowEnd").asText()).isEqualTo(WINDOW_END);
        assertThat(request.path("modelVersion").asText()).isEqualTo("test-model");
        assertThat(request.path("featureSchemaVersion").asText()).isEqualTo("test-features");
        mvc.perform(get(path(incident) + "/" + job + "/result")).andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("NORMAL"))
                .andExpect(jsonPath("$.jobId").value(job));
        mvc.perform(get(base + "/" + incident)).andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(get("/api/v1/environments/" + otherId + "/incidents/" + incident + "/rca-jobs/" + job + "/result"))
                .andExpect(status().isNotFound());
        runner.runNext();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void recordsUpstreamFailureAndDoesNotRetry() throws Exception {
        responseStatus.set(503);
        responseOverride.set("{\"detail\":\"private model internals\"}");
        String incident = incident();
        String job = job(incident, UUID.randomUUID().toString(), WINDOW_END);
        runner.runNext();
        assertThat(state(job)).isEqualTo("FAILED");
        String detail = mvc.perform(get(path(incident) + "/" + job)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("private model internals");
        assertThat(read(detail).path("reason").asText()).isNotBlank();
        mvc.perform(get(path(incident) + "/" + job + "/result")).andExpect(status().isConflict());
        runner.runNext();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedSuccessfulModelResponse() throws Exception {
        responseOverride.set("{\"jobId\":\"another-job\"}");
        String incident = incident();
        String job = job(incident, UUID.randomUUID().toString(), WINDOW_END);
        runner.runNext();
        assertThat(state(job)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT result_json FROM analysis_jobs WHERE id=?", String.class, job)).isNull();
    }

    @Test
    void concurrentRunnersClaimOnceAndCancellationWinsOverLateResult() throws Exception {
        String incident = incident();
        String job = job(incident, UUID.randomUUID().toString(), WINDOW_END);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var returned = new CountDownLatch(1);
            Callable<Void> compete = () -> {
                start.await();
                try { runner.runNext(); }
                finally { returned.countDown(); }
                return null;
            };
            var first = workers.submit(compete);
            var second = workers.submit(compete);
            start.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(state(job)).isEqualTo("RUNNING");
            assertThat(calls.get()).isEqualTo(1);
            mvc.perform(post(path(incident) + "/" + job + "/cancel"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertThat(state(job)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT result_json FROM analysis_jobs WHERE id=?", String.class, job)).isNull();
    }

    @Test
    void incidentClosureWinsOverRunningResult() throws Exception {
        String incident = incident();
        String job = job(incident, UUID.randomUUID().toString(), WINDOW_END);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(runner::runNext);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(patch(base + "/" + incident).contentType("application/json").content("{\"status\":\"RESOLVED\"}"))
                    .andExpect(status().isOk());
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
        }
        assertThat(state(job)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT reason FROM analysis_jobs WHERE id=?", String.class, job)).isEqualTo("INCIDENT_RESOLVED");
        assertThat(db.queryForObject("SELECT result_json FROM analysis_jobs WHERE id=?", String.class, job)).isNull();
    }

    @Test
    void expiresAbandonedWorkButPreservesLiveLeaseAndIngestionDelay() throws Exception {
        String expired = job(incident(), UUID.randomUUID().toString(), WINDOW_END);
        String live = job(incident(), UUID.randomUUID().toString(), WINDOW_END);
        String waiting = job(incident(), UUID.randomUUID().toString(), WINDOW_END);
        db.update("UPDATE analysis_jobs SET status='RUNNING',lease_expires_at=?,claim_token=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(60)), UUID.randomUUID().toString(), expired);
        db.update("UPDATE analysis_jobs SET status='RUNNING',lease_expires_at=?,claim_token=? WHERE id=?",
                Timestamp.from(Instant.now().plusSeconds(60)), UUID.randomUUID().toString(), live);
        db.update("UPDATE analysis_jobs SET ready_at=? WHERE id=?", Timestamp.from(Instant.now().plusSeconds(60)), waiting);
        runner.runNext();
        assertThat(state(expired)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT reason FROM analysis_jobs WHERE id=?", String.class, expired)).isEqualTo("WORKER_INTERRUPTED");
        assertThat(state(live)).isEqualTo("RUNNING");
        assertThat(state(waiting)).isEqualTo("QUEUED");
        assertThat(calls.get()).isZero();
    }

    @Test
    void leaseExpiryRejectsLateSuccessBeforeRecoveryTick() throws Exception {
        String incident = incident();
        String job = job(incident, UUID.randomUUID().toString(), WINDOW_END);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(runner::runNext);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE analysis_jobs SET lease_expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), job);
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("SELECT result_json FROM analysis_jobs WHERE id=?", String.class, job)).isNull();
        runner.runNext();
        assertThat(state(job)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT reason FROM analysis_jobs WHERE id=?", String.class, job)).isEqualTo("WORKER_INTERRUPTED");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void derivesDefaultWindowFromIncidentAndRejectsInvalidRequestedWindow() throws Exception {
        String incident = incident();
        for (String invalid : new String[]{"2026-01-01T00:00:31Z", "2026-01-01T00:00:30.001Z", "2099-01-01T00:00:30Z"}) {
            mvc.perform(post(path(incident)).contentType("application/json").content(
                    "{\"requestKey\":\"" + UUID.randomUUID() + "\",\"windowEnd\":\"" + invalid + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        String job = job(incident, UUID.randomUUID().toString(), null);
        mvc.perform(get(path(incident) + "/" + job))
                .andExpect(jsonPath("$.windowStart").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.windowEnd").value(WINDOW_END))
                .andExpect(jsonPath("$.readyAt").value("2026-01-01T00:01:00Z"));
        mvc.perform(post(path(incident)).contentType("application/json").content("{\"requestKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(path(incident) + "/" + job + "/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        runner.runNext();
        assertThat(calls.get()).isZero();
    }
}
