package com.sentrix.core.datasource;

import com.sentrix.core.environment.Environment;
import com.sentrix.core.environment.EnvironmentRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSourceApiTests {
    static final AtomicInteger upstreamStatus = new AtomicInteger(200);
    static final AtomicInteger redirectedRequests = new AtomicInteger();
    static final AtomicLong delayMs = new AtomicLong();
    static final AtomicLong bodyDelayMs = new AtomicLong();
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;
    static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    static final HttpServer upstream = startUpstream();
    static final String host = "http://127.0.0.1:" + upstream.getAddress().getPort();

    @Autowired MockMvc mvc;
    @Autowired EnvironmentRepository environments;
    @Autowired ObservationDataSourceRepository sources;
    String base;
    Long environmentId;

    static HttpServer startUpstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            for (String path : new String[]{"/prom/-/ready", "/tempo/ready", "/loki/ready"}) {
                server.createContext(path, exchange -> {
                    try (exchange) {
                        if (entered != null) entered.countDown();
                        if (release != null && !release.await(5, TimeUnit.SECONDS)) return;
                        Thread.sleep(delayMs.get());
                        int status = upstreamStatus.get();
                        if (status == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
                        exchange.sendResponseHeaders(status, 2);
                        Thread.sleep(bodyDelayMs.get());
                        exchange.getResponseBody().write("ok".getBytes());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            server.createContext("/redirect-target", exchange -> {
                redirectedRequests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sentrix.data-sources.allowed-base-urls",
                () -> host + "/prom," + host + "/tempo," + host + "/loki");
    }

    @BeforeEach
    void setUp() {
        sources.deleteAll();
        environments.deleteAll();
        upstreamStatus.set(200);
        redirectedRequests.set(0);
        delayMs.set(0);
        bodyDelayMs.set(0);
        entered = null;
        release = null;
        environmentId = environments.saveAndFlush(new Environment("Boutique", "sentrix")).getId();
        base = "/api/v1/environments/" + environmentId + "/data-sources";
    }

    @AfterEach
    void clean() {
        if (release != null) release.countDown();
        sources.deleteAll();
        environments.deleteAll();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
        executor.shutdownNow();
    }

    void configure(String type, String path, int timeout) throws Exception {
        mvc.perform(put(base + "/" + type).contentType("application/json")
                        .content(body("Source", host + path, timeout)))
                .andExpect(status().isOk());
    }

    static String body(String name, String url, int timeout) {
        return "{\"name\":\"" + name + "\",\"baseUrl\":\"" + url + "\",\"timeoutSeconds\":" + timeout + "}";
    }

    @Test
    void savesOneSourcePerTypeAndPersistsReadinessChecks() throws Exception {
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(content().json("[]"));
        for (String type : new String[]{"prometheus", "tempo", "loki"}) {
            String path = type.equals("prometheus") ? "/prom" : "/" + type;
            configure(type, path + "/", 2);
            mvc.perform(get(base)).andExpect(status().isOk());
            var stored = sources.findByEnvironmentIdAndType(environmentId, DataSourceType.parse(type)).orElseThrow();
            assertEquals(host + path, stored.baseUrl);
            assertEquals("NOT_TESTED", stored.status);
            assertNull(stored.lastCheckedAt);
            mvc.perform(post(base + "/" + type + "/test"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.type").value(type))
                    .andExpect(jsonPath("$.status").value("CONNECTED"))
                    .andExpect(jsonPath("$.httpStatus").value(200))
                    .andExpect(jsonPath("$.failureCode").isEmpty())
                    .andExpect(jsonPath("$.lastCheckedAt").exists())
                    .andExpect(jsonPath("$.latencyMs").isNumber());
            // Identical saves preserve checks and cannot create duplicate rows.
            configure(type, path, 2);
            assertEquals("CONNECTED", sources.findById(stored.id).orElseThrow().status);
        }
        mvc.perform(get(base)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("CONNECTED"));
        configure("prometheus", "/prom", 3);
        var reset = sources.findByEnvironmentIdAndType(environmentId, DataSourceType.PROMETHEUS).orElseThrow();
        assertEquals("NOT_TESTED", reset.status);
        assertNull(reset.lastCheckedAt);
        assertNull(reset.httpStatus);
        assertNull(reset.latencyMs);
    }

    @Test
    void isolatesEnvironmentSettingsAndRejectsUnknownResources() throws Exception {
        configure("prometheus", "/prom", 2);
        Long other = environments.saveAndFlush(new Environment("Other", "other")).getId();
        String otherBase = "/api/v1/environments/" + other + "/data-sources";
        mvc.perform(get(otherBase)).andExpect(content().json("[]"));
        mvc.perform(post(otherBase + "/prometheus/test")).andExpect(status().isNotFound());
        for (String type : new String[]{"mysql", "PROMETHEUS"}) {
            mvc.perform(post(base + "/" + type + "/test")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/environments/999999/data-sources")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/environments/999999/data-sources/prometheus")
                .contentType("application/json").content(body("Source", host + "/prom", 2)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidOrUnapprovedSettingsWithoutChangingSavedConfiguration() throws Exception {
        configure("prometheus", "/prom", 2);
        for (String invalid : new String[]{"{}", "not-json", body(" ", host + "/prom", 2),
                body("Source", host + "/prom", 0), body("Source", host + "/prom", 11),
                body("Source", "http://169.254.169.254", 2), body("Source", host + "/other", 2),
                body("Source", "file:///etc/passwd", 2), body("Source", host + "/prom?token=secret", 2),
                body("Source", "http://user:password@127.0.0.1", 2)}) {
            mvc.perform(put(base + "/prometheus").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(1, sources.count());
        assertEquals(host + "/prom", sources.findAll().getFirst().baseUrl);
    }

    @Test
    void recordsHttpFailuresWithoutFollowingRedirectsAndCanRecover() throws Exception {
        configure("loki", "/loki", 2);
        for (int code : new int[]{503, 401, 302}) {
            upstreamStatus.set(code);
            mvc.perform(post(base + "/loki/test")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.httpStatus").value(code))
                    .andExpect(jsonPath("$.failureCode").value(code == 302 ? "REDIRECT_REJECTED" : "HTTP_ERROR"));
        }
        assertEquals(0, redirectedRequests.get());
        upstreamStatus.set(200);
        mvc.perform(post(base + "/loki/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED"))
                .andExpect(jsonPath("$.failureCode").isEmpty());
    }

    @Test
    void boundsSlowResponseAndPersistsTimeout() throws Exception {
        configure("tempo", "/tempo", 1);
        delayMs.set(1800);
        assertTimeout(Duration.ofSeconds(4), () ->
            mvc.perform(post(base + "/tempo/test")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.failureCode").value("TIMEOUT"))
                    .andExpect(jsonPath("$.httpStatus").isEmpty()));
        assertEquals("TIMEOUT", sources.findAll().getFirst().failureCode);
    }

    @Test
    void doesNotWaitForReadinessResponseBody() throws Exception {
        configure("prometheus", "/prom", 1);
        bodyDelayMs.set(2500);
        assertTimeout(Duration.ofSeconds(2), () ->
            mvc.perform(post(base + "/prometheus/test")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONNECTED")));
    }

    @Test
    void slowTestCannotOverwriteNewerSettings() throws Exception {
        configure("prometheus", "/prom", 5);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        var pending = CompletableFuture.supplyAsync(() -> {
            try { return mvc.perform(post(base + "/prometheus/test")).andReturn().getResponse().getStatus(); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            configure("prometheus", "/prom", 2);
        } finally {
            release.countDown();
        }
        assertEquals(409, pending.get(5, TimeUnit.SECONDS));
        var stored = sources.findAll().getFirst();
        assertEquals(2, stored.timeoutSeconds);
        assertEquals("NOT_TESTED", stored.status);
    }
}
