package com.sentrix.core.telemetry;
import com.sentrix.core.datasource.PrometheusClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PodControllerTests {
 @Test void preservesZeroAndMissingAndScopesQuery() throws Exception {
  var client=mock(PrometheusClient.class);var connection=new PrometheusClient.Connection("sentrix","http://localhost",5);
  when(client.connection(1L)).thenReturn(connection);
  var mapper=new ObjectMapper();
  when(client.query(eq(connection),anyMap(),eq(false))).thenReturn(new PrometheusClient.QueryResult(mapper.readTree("["+row("member","1")+","+row("ready","0")+"]"),false));
  var result=new PodController(client).get(1L,"frontend");
  assertEquals(false,result.items().getFirst().ready());assertNull(result.items().getFirst().restarts());assertEquals("PARTIAL",result.dataStatus());
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new PodController(client).get(1L,"bad\""));
  String query=PodController.expression("sentrix","frontend");
  assertTrue(query.contains("owner_name=\"frontend\""));assertTrue(query.contains("on(namespace,pod,uid)"));
  Files.writeString(Path.of("target/pod-query.txt"),query);
 }
 @Test void rejectsForeignScopeAndNonIntegralCounts() throws Exception {
  var client=mock(PrometheusClient.class);var c=new PrometheusClient.Connection("sentrix","http://localhost",5);when(client.connection(1L)).thenReturn(c);
  for(String invalid:List.of(row("member","1").replace("sentrix","foreign"),row("restarts","1.2"))){
   when(client.query(eq(c),anyMap(),eq(false))).thenReturn(new PrometheusClient.QueryResult(new ObjectMapper().readTree("["+invalid+"]"),false));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new PodController(client).get(1L,"frontend"));
  }
 }
 static String row(String field,String value){return "{\"metric\":{\"namespace\":\"sentrix\",\"pod\":\"frontend-abc-xyz\",\"uid\":\"uid1\",\"field\":\""+field+"\"},\"value\":[1,\""+value+"\"]}";}
}
