package com.sentrix.core.telemetry;
import com.sentrix.core.datasource.PrometheusClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PodMetricsTests {
 static final String UID="97d1d5ef-203c-43dc-a345-72df4783703e", POD="frontend-564f67b847-q8fvm";
 final PrometheusClient client=mock(PrometheusClient.class);
 final PrometheusClient.Connection connection=new PrometheusClient.Connection("sentrix","http://localhost",5);
 final PodMetricsController controller=new PodMetricsController(client);
 final Instant from=Instant.ofEpochSecond(1000),to=Instant.ofEpochSecond(1060);
 void response(String rows) throws Exception {
  when(client.connection(1L)).thenReturn(connection);
  when(client.query(eq(connection),anyMap(),eq(true))).thenReturn(new PrometheusClient.QueryResult(new ObjectMapper().readTree(rows),false));
 }
 String row(String samples){return "[{\"metric\":{\"namespace\":\"sentrix\",\"pod\":\""+POD+"\",\"uid\":\""+UID+"\"},\"values\":"+samples+"}]";}
 PodMetricsController.Result get(){return controller.get(1L,"frontend",POD,UID,"cpu_usage_cores",from,to,30);}
 @Test void zeroMissingAndNoDataAreDistinct() throws Exception {
  response(row("[[1000,\"0\"]]"));var r=get();
  assertEquals("PARTIAL",r.dataStatus());assertEquals(0.0,r.points().getFirst().value());assertNull(r.points().getLast().value());
  response("[]");assertEquals("NO_DATA",get().dataStatus());
  response(row("[[1000,\"0.2\"],[1030,\"NaN\"]]"));assertTrue(get().warnings().contains("NON_FINITE_VALUES"));
 }
 @Test void rejectsWrongIdentityAndInvalidSamples() throws Exception {
  for(String data:List.of(row("[[1000,\"1\"]]").replace(UID,"wrong"),row("[[1000,\"1\"]]").replace("sentrix","other"),
   row("[[1001,\"1\"]]"),row("[[1000,\"-1\"]]"),row("[[1000,\"1\"],[1000,\"2\"]]"),row("[[1060,\"1\"]]"))) {
   response(data);assertEquals(502,assertThrows(ResponseStatusException.class,this::get).getStatusCode().value());
  }
 }
 @Test void validatesBeforeQueryAndWritesScopedQueries() throws Exception {
  assertThrows(ResponseStatusException.class,()->controller.get(1L,"frontend",POD,"bad\"","cpu_usage_cores",from,to,30));
  assertThrows(ResponseStatusException.class,()->controller.get(1L,"frontend",POD,UID,"request_rate",from,to,30));
  assertThrows(ResponseStatusException.class,()->controller.get(1L,"frontend",POD,UID,"cpu_usage_cores",from,from.plusSeconds(86400),5));
  verifyNoInteractions(client);
  for(String metric:List.of("cpu_usage_cores","memory_working_set_bytes")) {
   String query=PodMetricsController.expression("sentrix","frontend",POD,UID,metric);
   assertTrue(query.contains("owner_name=\"frontend\""));assertTrue(query.contains("uid=\""+UID+"\""));
   assertTrue(query.contains("id=~"));assertTrue(query.contains("on(namespace,pod,uid)"));
   Files.writeString(Path.of("target/pod-"+metric+".promql"),query);
  }
 }
}
