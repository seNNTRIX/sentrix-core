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
class TraceApiTests {
    static final String TRACE="11111111111111111111111111111111", PARENT="aaaaaaaaaaaaaaaa", CHILD="bbbbbbbbbbbbbbbb";
    static final AtomicReference<String> payload=new AtomicReference<>(), query=new AtomicReference<>(), path=new AtomicReference<>();
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
                requests.incrementAndGet();query.set(e.getRequestURI().getRawQuery());path.set(e.getRequestURI().getPath());
                byte[] body=payload.get().getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Location",URL+"/redirected");
                e.sendResponseHeaders(code.get(),body.length);e.getResponseBody().write(body);
            }});s.start();return s;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("sentrix.data-sources.allowed-base-urls",()->URL);}
    @BeforeEach void setup(){
        sources.deleteAll();environments.deleteAll();requests.set(0);code.set(200);
        long id=environments.saveAndFlush(new Environment("Traces","sentrix")).getId();
        var source=new ObservationDataSource(id,DataSourceType.TEMPO);source.configure("Tempo",URL,1);sources.saveAndFlush(source);
        base="/api/v1/environments/"+id+"/traces";payload.set("{\"metrics\":{}}");
    }
    @AfterEach void clean(){sources.deleteAll();environments.deleteAll();}
    @AfterAll static void stop(){server.stop(0);}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder search(){return get(base).param("service","frontend")
            .param("from","2023-11-14T22:13:20Z").param("to","2023-11-14T22:14:20Z");}
    static String span(String id,String parent,long offset) {
        return "{\"traceId\":\""+TRACE+"\",\"spanId\":\""+id+"\",\"parentSpanId\":\""+parent+"\",\"name\":\"GET /cart\",\"kind\":\"SPAN_KIND_SERVER\",\"startTimeUnixNano\":\""+(1700000000000000000L+offset)+"\",\"endTimeUnixNano\":\"1700000000010000000\",\"status\":{\"code\":\"STATUS_CODE_ERROR\"}}";
    }
    static String batch(String ns,String service,String spans) {return "{\"resource\":{\"attributes\":[{\"key\":\"service.namespace\",\"value\":{\"stringValue\":\""+ns+"\"}},{\"key\":\"service.name\",\"value\":{\"stringValue\":\""+service+"\"}}]},\"scopeSpans\":[{\"spans\":["+spans+"]}]}";}
    static String detail(String batches) {return "{\"trace\":{\"resourceSpans\":["+batches+"]}}";}
    @Test void searchScopesExactlyAndHandlesOmittedZeroAndBoundedResults() throws Exception {
        payload.set("{\"traces\":[{\"traceID\":\""+TRACE+"\",\"startTimeUnixNano\":\"1700000000000000001\"}],\"metrics\":{}}");
        mvc.perform(search().param("limit","1")).andExpect(status().isOk()).andExpect(jsonPath("$.limitReached").value(true))
                .andExpect(jsonPath("$.items[0].durationMs").value(0)).andExpect(jsonPath("$.items[0].startedAt").value("2023-11-14T22:13:20.000000001Z"));
        assertEquals("/api/search",path.get());
        assertTrue(URLDecoder.decode(query.get(),StandardCharsets.UTF_8).contains("{ resource.service.namespace = \"sentrix\" && resource.service.name = \"frontend\" }"));
        payload.set("{\"traces\":[{\"traceID\":\"abc\",\"startTimeUnixNano\":\"1700000000000000001\"}],\"metrics\":{}}");
        mvc.perform(search()).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].traceId").value("0".repeat(29)+"abc"));
        payload.set("{\"metrics\":{}}");mvc.perform(search()).andExpect(status().isOk()).andExpect(jsonPath("$.dataStatus").value("NO_DATA"));
        int before=requests.get();
        mvc.perform(search().param("limit","101")).andExpect(status().isBadRequest());
        mvc.perform(get(base).param("service","x\" || true").param("from","2023-11-14T22:13:20Z").param("to","2023-11-14T22:14:20Z")).andExpect(status().isBadRequest());
        mvc.perform(get(base+"/../secret")).andExpect(status().isNotFound());
        mvc.perform(get(base+"/not-an-id")).andExpect(status().isBadRequest());
        assertEquals(before,requests.get());
    }
    @Test void detailPreservesRelationshipsPrecisionAndOtelStatusWithHexOrBase64Ids() throws Exception {
        String first=span(PARENT,"",1).replace(TRACE,Base64.getEncoder().encodeToString(HexFormat.of().parseHex(TRACE)))
                .replace(PARENT,Base64.getEncoder().encodeToString(HexFormat.of().parseHex(PARENT))).replace("\"status\":{\"code\":\"STATUS_CODE_ERROR\"}","\"status\":{}");
        payload.set(detail(batch("sentrix","frontend",first)+","+batch("sentrix","cart",span(CHILD,PARENT,1000001))));
        mvc.perform(get(base+"/"+TRACE)).andExpect(status().isOk()).andExpect(jsonPath("$.spans.length()").value(2))
                .andExpect(jsonPath("$.spans[0].status").value("UNSET"))
                .andExpect(jsonPath("$.spans[0].startedAt").value("2023-11-14T22:13:20.000000001Z"))
                .andExpect(jsonPath("$.spans[1].parentSpanId").value(PARENT)).andExpect(jsonPath("$.spans[1].status").value("ERROR"))
                .andExpect(jsonPath("$.spans[1].durationMs").value(8.999999))
                .andExpect(jsonPath("$.spans[0].startNanos").doesNotExist());
        assertEquals("/api/v2/traces/"+TRACE,path.get());
    }
    @Test void detailOmitsOtherEnvironmentsAndReportsMissingParentsAndPartialUpstream() throws Exception {
        payload.set(detail(batch("other","secret-service",span(PARENT,"",0))+","+batch("sentrix","frontend",span(CHILD,PARENT,1))).replace("{\"trace\":","{\"status\":\"PARTIAL\",\"trace\":"));
        mvc.perform(get(base+"/"+TRACE)).andExpect(status().isOk()).andExpect(jsonPath("$.spans.length()").value(1))
                .andExpect(jsonPath("$.dataStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.warnings").value(org.hamcrest.Matchers.hasItems("OUTSIDE_ENVIRONMENT_OMITTED","MISSING_PARENT_SPANS","UPSTREAM_PARTIAL")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-service"))));
        payload.set(detail(batch("other","secret",span(PARENT,"",0))));
        mvc.perform(get(base+"/"+TRACE)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACE_NOT_FOUND"));
    }
    @Test void rejectsBadTimesIdsCyclesAndDuplicateSpansInsteadOfInventingAFlow() throws Exception {
        String good=detail(batch("sentrix","frontend",span(PARENT,"",1)));
        for(String bad:List.of(good.replace("1700000000010000000","0"),good.replace(TRACE,"22222222222222222222222222222222"),
                detail(batch("sentrix","frontend",span(PARENT,CHILD,1)+","+span(CHILD,PARENT,1))),
                detail(batch("sentrix","frontend",span(PARENT,"",1)+","+span(PARENT,"",1))),"{}")){
            payload.set(bad);mvc.perform(get(base+"/"+TRACE)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("INVALID_TEMPO_RESPONSE"));
        }
    }
    @Test void distinguishesMissingTraceFromUnavailableStorageAndConfiguration() throws Exception {
        code.set(404);mvc.perform(get(base+"/"+TRACE)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACE_NOT_FOUND"));
        code.set(503);mvc.perform(get(base+"/"+TRACE)).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("TEMPO_HTTP_ERROR"));
        code.set(302);int before=requests.get();mvc.perform(search()).andExpect(status().isBadGateway());assertEquals(before+1,requests.get());
        code.set(200);sources.deleteAll();mvc.perform(search()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEMPO_NOT_CONFIGURED"));
    }
}
