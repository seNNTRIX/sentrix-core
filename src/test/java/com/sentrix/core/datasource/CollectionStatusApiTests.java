package com.sentrix.core.datasource;

import com.sentrix.core.environment.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
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
class CollectionStatusApiTests {
    static final Map<String,String> bodies=new ConcurrentHashMap<>(),queries=new ConcurrentHashMap<>();
    static final Map<String,Integer> codes=new ConcurrentHashMap<>();
    static final AtomicInteger calls=new AtomicInteger();
    static final ExecutorService executor=Executors.newCachedThreadPool();
    static final HttpServer server=start();
    static final String URL="http://127.0.0.1:"+server.getAddress().getPort();
    @Autowired MockMvc mvc;
    @Autowired EnvironmentRepository environments;
    @Autowired ObservationDataSourceRepository sources;
    String base;Instant now;
    static HttpServer start(){
        try {
            var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.setExecutor(executor);
            s.createContext("/",e->{try(e){
                calls.incrementAndGet();String path=e.getRequestURI().getPath();
                String query=e.getRequestMethod().equals("POST")?new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8):e.getRequestURI().getRawQuery();
                queries.put(path,URLDecoder.decode(query==null?"":query,StandardCharsets.UTF_8));
                if(codes.getOrDefault(path,200)==408){try{Thread.sleep(1500);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
                byte[] body=bodies.getOrDefault(path,"{}").getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Location",URL+"/redirected");e.sendResponseHeaders(codes.getOrDefault(path,200),body.length);e.getResponseBody().write(body);
            }});s.start();return s;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("sentrix.data-sources.allowed-base-urls",()->URL);}
    @BeforeEach void setup(){
        sources.deleteAll();environments.deleteAll();bodies.clear();queries.clear();codes.clear();calls.set(0);
        now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        long id=environments.saveAndFlush(new Environment("Collection","sentrix")).getId();
        for(var type:DataSourceType.values()){
            var source=new ObservationDataSource(id,type);source.configure(type.name(),URL,1);sources.saveAndFlush(source);
        }
        base="/api/v1/environments/"+id+"/collection-status";
        bodies.put("/api/v1/query",prom(now.minusSeconds(10),now.minusSeconds(600)));
        bodies.put("/loki/api/v1/query_range",logs(now.minusSeconds(20),"sentrix"));
        bodies.put("/api/search",traces(now.minusSeconds(30),"sentrix"));
    }
    @AfterEach void clean(){sources.deleteAll();environments.deleteAll();}
    @AfterAll static void stop(){server.stop(0);executor.shutdownNow();}
    static String prom(Instant deployment,Instant request){return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
            +metric("deployment_metrics",deployment)+","+metric("request_metrics",request)+"]}}";}
    static String metric(String signal,Instant time){return "{\"metric\":{\"signal\":\""+signal+"\"},\"value\":[9999999999,\""+time.getEpochSecond()+"\"]}";}
    static String logs(Instant time,String namespace){return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[{\"stream\":{\"k8s_namespace_name\":\""+namespace+"\",\"k8s_deployment_name\":\"frontend\"},\"values\":[[\""+time.getEpochSecond()+"000000001\",\"line\"]]}]}}";}
    static String traces(Instant time,String namespace){return "{\"traces\":[{\"traceID\":\"abc\",\"startTimeUnixNano\":\""+time.getEpochSecond()+"000000000\",\"spanSets\":[{\"spans\":[{\"attributes\":[{\"key\":\"service.namespace\",\"value\":{\"stringValue\":\""+namespace+"\"}},{\"key\":\"service.name\",\"value\":{\"stringValue\":\"frontend\"}}]}]}]}],\"metrics\":{\"totalJobs\":1,\"completedJobs\":1}}";}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(){return get(base).param("service","frontend");}

    @Test void usesOriginalTimesAndScopedQueriesWithoutSavingProbeResults() throws Exception {
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals.length()").value(4))
                .andExpect(jsonPath("$.signals[0].freshness").value("RECENT"))
                .andExpect(jsonPath("$.signals[0].observedAt").value(now.minusSeconds(10).toString()))
                .andExpect(jsonPath("$.signals[1].freshness").value("STALE"))
                .andExpect(jsonPath("$.signals[2].freshness").value("RECENT"))
                .andExpect(jsonPath("$.signals[3].freshness").value("RECENT"));
        assertEquals(3,calls.get());
        assertTrue(queries.get("/api/v1/query").contains("timestamp(kube_deployment_status_replicas{namespace=\"sentrix\",deployment=\"frontend\"})"));
        assertTrue(queries.get("/loki/api/v1/query_range").contains("k8s_namespace_name=\"sentrix\",k8s_deployment_name=\"frontend\""));
        assertTrue(queries.get("/api/search").contains("resource.service.namespace = \"sentrix\" && resource.service.name = \"frontend\" } with (most_recent=true)"));
        for(var source:sources.findAll()){assertEquals("NOT_TESTED",source.status);assertNull(source.lastCheckedAt);}
    }
    @Test void distinguishesEmptyFromUnconfiguredAndDoesNotInventNoTraffic() throws Exception {
        bodies.put("/api/v1/query","{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        bodies.put("/loki/api/v1/query_range","{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}");
        bodies.put("/api/search","{\"metrics\":{}}");
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[*].freshness",org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("NO_DATA"))))
                .andExpect(jsonPath("$.signals[0].observedAt").isEmpty()).andExpect(jsonPath("$.signals[2].warnings").value(org.hamcrest.Matchers.hasItem("CAUSE_UNDETERMINED")));
        sources.deleteAll();int before=calls.get();
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[*].queryStatus",org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("NOT_CONFIGURED"))));
        assertEquals(before,calls.get());
    }
    @Test void isolatesProviderTimeoutAndRedirectWithoutLosingOtherSignals() throws Exception {
        codes.put("/api/search",408);
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[0].freshness").value("RECENT"))
                .andExpect(jsonPath("$.signals[3].failureCode").value("TEMPO_TIMEOUT")).andExpect(jsonPath("$.signals[3].freshness").value("UNKNOWN"));
        codes.clear();codes.put("/loki/api/v1/query_range",302);int before=calls.get();
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[2].failureCode").value("LOKI_HTTP_ERROR"))
                .andExpect(jsonPath("$.signals[3].queryStatus").value("OK"));
        assertEquals(3,calls.get()-before);
        codes.put("/api/v1/query",503);
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[0].queryStatus").value("ERROR"))
                .andExpect(jsonPath("$.signals[1].queryStatus").value("ERROR"));
    }
    @Test void partialAndFutureEvidenceIsUnknown() throws Exception {
        bodies.put("/api/v1/query",prom(now.minusSeconds(10),now.minusSeconds(20)).replace("\"data\":","\"warnings\":[\"partial\"],\"data\":"));
        bodies.put("/api/search",traces(now.minusSeconds(30),"sentrix").replace("\"totalJobs\":1","\"totalJobs\":2"));
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[0].freshness").value("UNKNOWN"))
                .andExpect(jsonPath("$.signals[3].queryStatus").value("PARTIAL"));
        bodies.put("/api/v1/query",prom(now.plusSeconds(600),now.minusSeconds(10)));
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[0].freshness").value("UNKNOWN"))
                .andExpect(jsonPath("$.signals[0].warnings").value(org.hamcrest.Matchers.hasItem("FUTURE_TIMESTAMP")));
    }
    @Test void rejectsInvalidInputCrossScopeAndMalformedEvidence() throws Exception {
        mvc.perform(get(base)).andExpect(status().isBadRequest());
        mvc.perform(get(base).param("service","frontend\"}" )).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/environments/999999/collection-status").param("service","frontend")).andExpect(status().isNotFound());
        assertEquals(0,calls.get());
        bodies.put("/loki/api/v1/query_range",logs(now.minusSeconds(10),"other"));
        bodies.put("/api/search",traces(now.minusSeconds(10),"other"));
        bodies.put("/api/v1/query",prom(now.minusSeconds(10),now.minusSeconds(10)).replace("\"deployment_metrics\"","\"unexpected\""));
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.signals[*].queryStatus",org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ERROR"))));
    }
    @Test void thresholdIsInclusiveAndMissingDataNeverBecomesZero() {
        assertEquals("RECENT",CollectionStatusController.observed("logs","loki","LOG_EVENT_TIME",now.minusSeconds(180),now,false).freshness());
        assertEquals("STALE",CollectionStatusController.observed("logs","loki","LOG_EVENT_TIME",now.minusSeconds(180).minusNanos(1),now,false).freshness());
        assertNull(CollectionStatusController.observed("logs","loki","LOG_EVENT_TIME",null,now,false).ageSeconds());
    }
    @Test void emitsPromtoolFixtureForOriginalTimestampsAndHistoricalSamples() throws Exception {
        var yaml="""
                evaluation_interval: 30s
                tests:
                  - interval: 30s
                    input_series:
                      - series: 'kube_deployment_status_replicas{namespace="sentrix",deployment="frontend"}'
                        values: '0 _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _'
                      - series: 'kube_deployment_status_replicas{namespace="other",deployment="frontend"}'
                        values: '9+0x20'
                      - series: 'traces_span_metrics_calls_total{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_SERVER"}'
                        values: '0+0x20'
                    promql_expr_test:
                      - expr: '%s'
                        eval_time: 10m
                        exp_samples:
                          - labels: '{signal="deployment_metrics"}'
                            value: 0
                          - labels: '{signal="request_metrics"}'
                            value: 600
                      - expr: '%s'
                        eval_time: 41m
                        exp_samples: []
                """.formatted(CollectionStatusController.metricsExpression("sentrix","frontend"),CollectionStatusController.metricsExpression("sentrix","frontend"));
        Files.writeString(Path.of("target","collection-query-tests.yaml"),yaml);
    }
}
