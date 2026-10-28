package com.sentrix.core.datasource;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ServiceMapTests {
    static TraceController.Span span(String id,String parent,String service,String kind,String status,double duration) {
        return new TraceController.Span(id,parent,service,"op",kind,status,Instant.EPOCH,Instant.EPOCH.plusMillis((long)duration),duration,0);
    }
    static TraceController.TraceDetail trace(List<TraceController.Span> spans) {
        return new TraceController.TraceDetail(1L,"sentrix","a".repeat(32),Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,0,"PARTIAL",List.of("MISSING_PARENT_SPANS"),spans);
    }
    @Test void groupsOnlyDirectClientServerPairsAndKeepsUnsetDistinct() {
        var response=ServiceMapController.build(trace(List.of(
                span("1","","front","CLIENT","UNSET",20),
                span("2","1","cart","SERVER","ERROR",10),
                span("3","1","cart","SERVER","UNSET",30),
                span("4","missing","payment","SERVER","OK",9),
                span("5","2","db","INTERNAL","OK",3))));
        assertEquals(1,response.edges().size());
        var edge=response.edges().getFirst();assertEquals("front",edge.source());assertEquals("cart",edge.target());
        assertEquals(2,edge.observedCalls());assertEquals(1,edge.explicitErrorCount());assertEquals(1,edge.unsetStatusCount());
        assertEquals(20,edge.meanServerDurationMs());assertEquals(30,edge.maxServerDurationMs());
        assertTrue(response.warnings().contains("MISSING_PARENT_SPANS"));assertEquals("SINGLE_TRACE",response.scope());
    }
    @Test void doesNotInventEdgesForMissingParentOrUnknownService() {
        var result=ServiceMapController.build(trace(List.of(span("1","","unknown","CLIENT","UNSET",1),span("2","1","cart","SERVER","OK",1))));
        assertTrue(result.edges().isEmpty());assertEquals("NO_OBSERVED_EDGES",result.dataStatus());
        assertTrue(result.warnings().contains("UNIDENTIFIED_SERVICE_EDGE_OMITTED"));
    }
    @Test void endpointRequiresTraceAndDelegatesEnvironmentIsolation() throws Exception {
        var traces=mock(TraceController.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ServiceMapController(traces)).build();
        mvc.perform(get("/api/v1/environments/1/service-map")).andExpect(status().isBadRequest());verifyNoInteractions(traces);
        when(traces.detail(1L,"a".repeat(32))).thenReturn(trace(List.of()));
        mvc.perform(get("/api/v1/environments/1/service-map").param("traceId","a".repeat(32)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("SINGLE_TRACE"));
        verify(traces).detail(1L,"a".repeat(32));
    }
}
