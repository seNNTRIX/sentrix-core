package com.sentrix.core.datasource;

import com.sentrix.core.environment.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
class LogApiTests {
    static final AtomicReference<String> payload=new AtomicReference<>(), query=new AtomicReference<>();
    static final AtomicInteger requests=new AtomicInteger(), code=new AtomicInteger(200);
    static final HttpServer server=start();
    static final String URL="http://127.0.0.1:"+server.getAddress().getPort();
    @Autowired MockMvc mvc;
    @Autowired EnvironmentRepository environments;
    @Autowired ObservationDataSourceRepository sources;
    String base;
    static HttpServer start() {
        try {
            var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            s.createContext("/",e->{try(e){
                requests.incrementAndGet(); query.set(e.getRequestURI().getRawQuery());
                assertEquals("GET",e.getRequestMethod());
                assertEquals("/loki/api/v1/query_range",e.getRequestURI().getPath());
                byte[] body=payload.get().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Location",URL+"/redirected");
                e.sendResponseHeaders(code.get(),body.length);e.getResponseBody().write(body);
            }});s.start();return s;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("sentrix.data-sources.allowed-base-urls",()->URL);}
    @BeforeEach void setup(){
        sources.deleteAll();environments.deleteAll();requests.set(0);code.set(200);query.set(null);
        long id=environments.saveAndFlush(new Environment("Logs","sentrix")).getId();
        var source=new ObservationDataSource(id,DataSourceType.LOKI);source.configure("Loki",URL,1);sources.saveAndFlush(source);
        base="/api/v1/environments/"+id+"/logs";
        payload.set(body("[]"));
    }
    @AfterEach void clean(){sources.deleteAll();environments.deleteAll();}
    @AfterAll static void stop(){server.stop(0);}
    static String body(String result){return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":"+result+"}}";}
    static String stream(String pod,String values){return "{\"stream\":{\"k8s_namespace_name\":\"sentrix\",\"k8s_deployment_name\":\"frontend\",\"k8s_pod_name\":\""+pod+"\"},\"values\":"+values+"}";}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(){return get(base).param("service","frontend")
            .param("from","2023-11-14T22:13:20Z").param("to","2023-11-14T22:14:20Z");}
    @Test void sortsAcrossStreamsPreservesNanosecondsAndBoundsResults() throws Exception {
        payload.set(body("["+stream("pod-a","[[\"1700000000000000001\",\"old\"],[\"1700000000000000003\",\"new <script>\",{\"k8s_container_name\":\"app\"}]]")+","+stream("pod-b","[[\"1700000000000000002\",\"middle\"]]")+"]"));
        mvc.perform(request().param("limit","2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.items[0].message").value("new <script>"))
                .andExpect(jsonPath("$.items[0].timestampNanos").value("1700000000000000003"))
                .andExpect(jsonPath("$.items[0].container").value("app"))
                .andExpect(jsonPath("$.items[1].pod").value("pod-b"));
        String decoded=URLDecoder.decode(query.get(),StandardCharsets.UTF_8);
        assertTrue(decoded.contains("limit=3"));assertTrue(decoded.contains("direction=backward"));
        assertTrue(decoded.contains("k8s_namespace_name=\"sentrix\",k8s_deployment_name=\"frontend\""));
    }
    @Test void literalSearchCannotInjectLogqlAndEmptyIsNotAnError() throws Exception {
        mvc.perform(request().param("search","\" |~ `.*` \\ error")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("NO_DATA")).andExpect(jsonPath("$.truncated").value(false));
        String decoded=URLDecoder.decode(query.get(),StandardCharsets.UTF_8);
        assertTrue(decoded.contains("|= \"\\\" |~ `.*` \\\\ error\""));
        int before=requests.get();
        mvc.perform(request().param("limit","501")).andExpect(status().isBadRequest());
        mvc.perform(request().param("search","bad\nline")).andExpect(status().isBadRequest());
        mvc.perform(get(base).param("service","x\"}").param("from","2023-11-14T22:13:20Z").param("to","2023-11-14T22:14:20Z")).andExpect(status().isBadRequest());
        assertEquals(before,requests.get());
    }
    @Test void rejectsCrossEnvironmentOrOutOfRangeDataAndUpstreamFailures() throws Exception {
        String good=body("["+stream("pod","[[\"1700000000000000001\",\"line\"]]")+"]");
        for(String bad:List.of(good.replace("sentrix","other"),good.replace("1700000000000000001","1700000060000000000"),good.replace("1700000000000000001","oops"),"not-json")){
            payload.set(bad);mvc.perform(request()).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("INVALID_LOKI_RESPONSE"));
        }
        payload.set(body("[]"));code.set(302);
        int before=requests.get();mvc.perform(request()).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("LOKI_HTTP_ERROR"));
        assertEquals(before+1,requests.get());
        code.set(200);payload.set("{\"status\":\"error\"}");
        mvc.perform(request()).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("LOKI_QUERY_FAILED"));
        sources.deleteAll();mvc.perform(request()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOKI_NOT_CONFIGURED"));
    }

    static String matrix(String values) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{\"k8s_namespace_name\":\"sentrix\",\"k8s_deployment_name\":\"frontend\"},\"values\":"+values+"}]}}";
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder volumeRequest() {
        return get(base+"/volume").param("service","frontend").param("from","2023-11-14T22:13:20Z").param("to","2023-11-14T22:15:20Z");
    }
    @Test void volumeCountsAllLinesAndPreservesMissingBuckets() throws Exception {
        payload.set(matrix("[[1700000060,\"701\"]]"));
        mvc.perform(volumeRequest().param("search","\" |~ `.*`")) .andExpect(status().isOk())
            .andExpect(jsonPath("$.buckets.length()").value(2)).andExpect(jsonPath("$.buckets[0].count").value(701))
            .andExpect(jsonPath("$.buckets[1].count").isEmpty()).andExpect(jsonPath("$.dataStatus").value("PARTIAL"))
            .andExpect(jsonPath("$.intervalBounds").value("START_EXCLUSIVE_END_INCLUSIVE"));
        String decoded=URLDecoder.decode(query.get(),StandardCharsets.UTF_8);
        assertTrue(decoded.contains("count_over_time"));assertTrue(decoded.contains("start=2023-11-14T22:14:20Z"));
        assertTrue(decoded.contains("end=2023-11-14T22:15:20Z"));assertTrue(decoded.contains("step=60"));assertFalse(decoded.contains("limit="));
        assertTrue(decoded.contains("|= \"\\\" |~ `.*`\""));
        payload.set(matrix("[[1700000060,\"0\"],[1700000120,\"2\"]]"));
        mvc.perform(volumeRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.dataStatus").value("AVAILABLE"))
            .andExpect(jsonPath("$.buckets[0].count").value(0));
        payload.set("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");
        mvc.perform(volumeRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.dataStatus").value("NO_DATA"));
    }
    @Test void volumeRejectsBadBoundsBeforeCallingLoki() throws Exception {
        mvc.perform(volumeRequest().param("bucketSeconds","61")).andExpect(status().isBadRequest());
        mvc.perform(volumeRequest().param("bucketSeconds","0")).andExpect(status().isBadRequest());
        mvc.perform(volumeRequest().param("search","bad\n")).andExpect(status().isBadRequest());
        mvc.perform(get(base+"/volume").param("service","frontend").param("from","2023-11-14T00:00:00Z")
            .param("to","2023-11-15T00:00:00Z")).andExpect(status().isBadRequest());
        assertEquals(0,requests.get());
    }
    @Test void volumeRejectsWrongScopeMalformedCountsAndUpstreamErrors() throws Exception {
        String good=matrix("[[1700000060,\"2\"]]");
        for(String bad:List.of(good.replace("sentrix","other"),good.replace("frontend","cartservice"),
                matrix("[[1700000060,\"1.5\"]]"),matrix("[[1700000060,\"-1\"]]"),matrix("[[1700000060,\"NaN\"]]"),
                matrix("[[1700000000,\"1\"]]"),matrix("[[1700000061,\"1\"]]"),matrix("[[1700000060,\"1\"],[1700000060,\"2\"]]"))) {
            payload.set(bad);mvc.perform(volumeRequest()).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("INVALID_LOKI_RESPONSE"));
        }
        payload.set(good);code.set(500);mvc.perform(volumeRequest()).andExpect(status().isBadGateway());
        code.set(200);sources.deleteAll();mvc.perform(volumeRequest()).andExpect(status().isConflict());
    }
}
