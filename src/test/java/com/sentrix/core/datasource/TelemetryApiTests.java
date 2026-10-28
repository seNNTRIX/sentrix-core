package com.sentrix.core.datasource;

import com.sentrix.core.environment.Environment;
import com.sentrix.core.environment.EnvironmentRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelemetryApiTests {
    static final AtomicReference<String> payload = new AtomicReference<>();
    static final AtomicReference<String> form = new AtomicReference<>();
    static final AtomicInteger httpStatus = new AtomicInteger(200);
    static final AtomicInteger requests = new AtomicInteger();
    static final AtomicLong bodyDelay = new AtomicLong();
    static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer server = startServer();
    static final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/prom";
    static final String FROM = "2023-11-14T22:13:20Z"; // 1700000000
    static final String TO = "2023-11-14T22:14:50Z";

    @Autowired MockMvc mvc;
    @Autowired EnvironmentRepository environments;
    @Autowired ObservationDataSourceRepository sources;
    String base;
    Long id;

    static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    if (!exchange.getRequestMethod().equals("POST") || !Set.of("/prom/api/v1/query", "/prom/api/v1/query_range")
                            .contains(exchange.getRequestURI().getPath())) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    form.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = payload.get().getBytes(StandardCharsets.UTF_8);
                    if (httpStatus.get() == 302) exchange.getResponseHeaders().set("Location", "/redirected");
                    exchange.sendResponseHeaders(httpStatus.get(), bytes.length);
                    Thread.sleep(bodyDelay.get());
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("sentrix.data-sources.allowed-base-urls", () -> url);
    }

    @BeforeEach
    void setUp() {
        sources.deleteAll();
        environments.deleteAll();
        id = environments.saveAndFlush(new Environment("Telemetry test", "other-ns")).getId();
        var source = new ObservationDataSource(id, DataSourceType.PROMETHEUS);
        source.configure("Prometheus", url, 1);
        sources.saveAndFlush(source);
        base = "/api/v1/environments/" + id;
        httpStatus.set(200);
        requests.set(0);
        bodyDelay.set(0);
        form.set(null);
        payload.set(envelope("matrix", "[]"));
    }

    @AfterEach
    void clean() { sources.deleteAll(); environments.deleteAll(); }

    @AfterAll
    static void stopServer() { server.stop(0); executor.shutdownNow(); }

    static String envelope(String type, String result) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"" + type + "\",\"result\":" + result + "}}";
    }

    static String series(String label, String values) {
        return "[{\"metric\":{\"" + label + "\":\"frontend\"},\"values\":" + values + "}]";
    }

    Map<String, String> sentParameters() {
        var params = new HashMap<String, String>();
        for (String part : form.get().split("&")) {
            String[] entry = part.split("=", 2);
            params.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder metric(String name) {
        return get(base + "/metrics").param("service", "frontend").param("metric", name)
                .param("from", FROM).param("to", TO).param("stepSeconds", "30");
    }

    @Test
    void discoversActualDeploymentsSortedAndScopesToRegisteredNamespace() throws Exception {
        payload.set(envelope("vector", """
                [{"metric":{"deployment":"frontend"},"value":[1700000000.123,"1699999000"]},
                 {"metric":{"deployment":"checkoutservice"},"value":[1700000000.123,"1699998000"]}]
                """));
        mvc.perform(get(base + "/services")).andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("other-ns"))
                .andExpect(jsonPath("$.dataStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].name").value("checkoutservice"))
                .andExpect(jsonPath("$.items[0].kind").value("Deployment"))
                .andExpect(jsonPath("$.items[1].createdAt").value("2023-11-14T21:56:40Z"));
        assertEquals("max by (deployment) (kube_deployment_created{namespace=\"other-ns\"})", sentParameters().get("query"));
        payload.set(envelope("vector", "[]"));
        mvc.perform(get(base + "/services")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("NO_DATA")).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void preservesZeroAndMarksMissingOrNonFinitePointsInsteadOfInventingData() throws Exception {
        payload.set(envelope("matrix", series("deployment", "[[1700000000,\"0\"],[1700000060,\"NaN\"]]")));
        mvc.perform(metric("cpu_usage_cores")).andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("cores"))
                .andExpect(jsonPath("$.dataStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].value").value(0.0))
                .andExpect(jsonPath("$.points[1].value").isEmpty())
                .andExpect(jsonPath("$.points[2].value").isEmpty())
                .andExpect(jsonPath("$.warnings[0]").value("NON_FINITE_VALUES"));
        assertEquals("2023-11-14T22:14:20Z", sentParameters().get("end"));
        assertTrue(sentParameters().get("query").contains("owner_name=\"frontend\""));
        payload.set(envelope("matrix", "[]"));
        mvc.perform(metric("cpu_usage_cores")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("NO_DATA"))
                .andExpect(jsonPath("$.points[0].value").isEmpty());
    }

    @Test
    void supportsMetricCatalogWithNamespaceScopeAndUpstreamWarnings() throws Exception {
        for (String metric : List.of("cpu_usage_cores", "memory_working_set_bytes", "request_rate", "error_ratio",
                "latency_p50_ms", "latency_p90_ms", "latency_p99_ms")) {
            String label = metric.startsWith("cpu") || metric.startsWith("memory") ? "deployment" : "service_name";
            payload.set(envelope("matrix", series(label, "[[1700000000,\"0\"],[1700000030,\"0.5\"],[1700000060,\"1\"]]")));
            mvc.perform(metric(metric)).andExpect(status().isOk()).andExpect(jsonPath("$.dataStatus").value("AVAILABLE"));
            assertTrue(sentParameters().get("query").contains("namespace=\"other-ns\""));
            if (label.equals("service_name")) assertTrue(sentParameters().get("query").contains("span_kind=\"SPAN_KIND_SERVER\""));
        }
        payload.set(payload.get().replace("\"status\":\"success\"", "\"warnings\":[\"private upstream detail\"],\"status\":\"success\""));
        mvc.perform(metric("latency_p99_ms")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.warnings[0]").value("UPSTREAM_WARNING"));
    }

    @Test
    void validatesQueryBeforeContactingPrometheus() throws Exception {
        for (var request : List.of(metric("anything"), metric("request_rate").param("service", "bad\"}"),
                metric("request_rate").param("from", "not-time"), metric("request_rate").param("to", FROM),
                metric("request_rate").param("stepSeconds", "0"), metric("request_rate").param("stepSeconds", "3601"),
                metric("request_rate").param("from", "2023-11-14T22:13:20.001Z"),
                metric("request_rate").param("to", "2023-11-16T22:13:20Z"),
                metric("request_rate").param("to", "2023-11-15T22:13:20Z").param("stepSeconds", "5"))) {
            // param appends values; replace to exercise the intended invalid value.
            request.with(r -> { for (String key : List.of("service", "from", "to", "stepSeconds")) {
                String[] values = r.getParameterValues(key);
                if (values != null) r.setParameter(key, values[values.length - 1]);
            } return r; });
            mvc.perform(request).andExpect(status().isBadRequest());
        }
        assertEquals(0, requests.get());
    }

    @Test
    void distinguishesMissingEnvironmentConfigurationAndRevokedTarget() throws Exception {
        mvc.perform(get("/api/v1/environments/999999/services")).andExpect(status().isNotFound());
        var source = sources.findAll().getFirst();
        source.configure("Prometheus", "http://127.0.0.1:1", 1);
        sources.saveAndFlush(source);
        mvc.perform(get(base + "/services")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TARGET_NOT_ALLOWED"));
        sources.deleteAll();
        mvc.perform(get(base + "/services")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROMETHEUS_NOT_CONFIGURED"));
        assertEquals(0, requests.get());
    }

    @Test
    void errorsAreNotSuccessfulEmptyChartsAndRedirectsAreNotFollowed() throws Exception {
        for (int code : new int[]{401, 503, 302}) {
            httpStatus.set(code);
            mvc.perform(metric("request_rate")).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("PROMETHEUS_HTTP_ERROR"))
                    .andExpect(jsonPath("$.upstreamStatus").value(code));
        }
        assertEquals(3, requests.get());
        httpStatus.set(200);
        for (String invalid : List.of("not json", envelope("vector", "[]"),
                envelope("matrix", series("service_name", "[[1700000001,\"3\"]]")),
                envelope("matrix", series("service_name", "[[1700000000,\"oops\"]]")))) {
            payload.set(invalid);
            mvc.perform(metric("request_rate")).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("INVALID_PROMETHEUS_RESPONSE"));
        }
        payload.set("{\"status\":\"error\",\"error\":\"private credentials\"}");
        mvc.perform(metric("request_rate")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROMETHEUS_QUERY_FAILED"));
    }

    @Test
    void limitsBodySizeAndBoundsSlowBodies() throws Exception {
        payload.set("x".repeat(4 * 1024 * 1024 + 1));
        mvc.perform(metric("request_rate")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("RESPONSE_TOO_LARGE"));
        payload.set(envelope("matrix", "[]"));
        bodyDelay.set(3000);
        assertTimeout(Duration.ofSeconds(3), () -> mvc.perform(metric("request_rate"))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("PROMETHEUS_TIMEOUT")));
    }
}
