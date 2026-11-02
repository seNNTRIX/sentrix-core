package com.sentrix.core.incident;
import com.sentrix.core.environment.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class IncidentTests {
 @Autowired MockMvc mvc; @Autowired EnvironmentRepository env; @Autowired JdbcTemplate db; @Autowired ObjectMapper mapper;
 Long id,other;String base;
 @BeforeEach void setup(){id=env.saveAndFlush(new Environment("incident-"+UUID.randomUUID(),"sentrix")).getId();other=env.saveAndFlush(new Environment("other-"+UUID.randomUUID(),"other")).getId();base="/api/v1/environments/"+id+"/incidents";}
 @AfterEach void cleanup(){db.update("DELETE FROM analysis_jobs WHERE incident_id IN (SELECT id FROM incidents WHERE environment_id=?)",id);db.update("DELETE FROM incidents WHERE environment_id=?",id);env.deleteById(id);env.deleteById(other);}
 String body(String key){return "{\"requestKey\":\""+key+"\",\"service\":\"frontend\",\"title\":\"응답 지연 확인\",\"description\":\"수동 기록\",\"occurredAt\":\"2026-01-01T00:00:00Z\"}";}
 String create()throws Exception{return mapper.readTree(mvc.perform(post(base).contentType("application/json").content(body(UUID.randomUUID().toString()))).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("MANUAL")).andReturn().getResponse().getContentAsString()).path("id").asText();}
 @Test void recordsFilterPaginationAndIsolation()throws Exception{
  String key=UUID.randomUUID().toString();var first=mvc.perform(post(base).contentType("application/json").content(body(key))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  mvc.perform(post(base).contentType("application/json").content(body(key))).andExpect(content().json(first));
  mvc.perform(post(base).contentType("application/json").content(body(key).replace("응답 지연 확인","다른 기록"))).andExpect(status().isConflict());
  create();mvc.perform(get(base).param("size","1").param("service","frontend").param("status","OPEN")).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items.length()").value(1));
  mvc.perform(get(base).param("to","2026-01-01T00:00:00Z")).andExpect(jsonPath("$.totalElements").value(0));
  String incident=mapper.readTree(first).path("id").asText();mvc.perform(get("/api/v1/environments/"+other+"/incidents/"+incident)).andExpect(status().isNotFound());
  mvc.perform(get(base).param("size","51")).andExpect(status().isBadRequest());
  mvc.perform(post(base).contentType("application/json").content(body(UUID.randomUUID().toString()).replace("2026-01-01","2099-01-01"))).andExpect(status().isBadRequest());
 }
 @Test void blockedJobsAreIdempotentCancelableAndNeverSuccessful()throws Exception{
  String path=base+"/"+create(),key=UUID.randomUUID().toString(),input="{\"requestKey\":\""+key+"\"}";
  String json=mvc.perform(post(path+"/rca-jobs").contentType("application/json").content(input)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED_MODEL_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
  String job=mapper.readTree(json).path("id").asText();mvc.perform(post(path+"/rca-jobs").contentType("application/json").content(input)).andExpect(content().json(json));
  mvc.perform(post(path+"/rca-jobs").contentType("application/json").content("{\"requestKey\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isConflict());
  mvc.perform(get(path+"/rca-jobs/"+job)).andExpect(jsonPath("$.reason").value("MODEL_UNAVAILABLE"));
  mvc.perform(post(path+"/rca-jobs/"+job+"/cancel")).andExpect(jsonPath("$.status").value("CANCELLED"));
  mvc.perform(post(path+"/rca-jobs").contentType("application/json").content(input)).andExpect(jsonPath("$.id").value(job)).andExpect(jsonPath("$.status").value("CANCELLED"));
  mvc.perform(get(path+"/rca-jobs")).andExpect(jsonPath("$.totalElements").value(1));
  mvc.perform(patch(path).contentType("application/json").content("{\"status\":\"RESOLVED\"}")).andExpect(jsonPath("$.resolvedAt").isNotEmpty());
  mvc.perform(post(path+"/rca-jobs").contentType("application/json").content("{\"requestKey\":\""+UUID.randomUUID()+"\"}")).andExpect(status().isConflict());
  mvc.perform(patch(path).contentType("application/json").content("{\"status\":\"OPEN\"}")).andExpect(jsonPath("$.resolvedAt").isEmpty());
 }
 @Test void resolvingCancelsBlockedWorkAndOtherEnvironmentCannotCancel()throws Exception{
  String incident=create(),path=base+"/"+incident;
  String job=mapper.readTree(mvc.perform(post(path+"/rca-jobs").contentType("application/json").content("{\"requestKey\":\""+UUID.randomUUID()+"\"}")).andReturn().getResponse().getContentAsString()).path("id").asText();
  mvc.perform(post("/api/v1/environments/"+other+"/incidents/"+incident+"/rca-jobs/"+job+"/cancel")).andExpect(status().isNotFound());
  mvc.perform(patch(path).contentType("application/json").content("{\"status\":\"RESOLVED\"}")).andExpect(status().isOk());
  mvc.perform(get(path+"/rca-jobs/"+job)).andExpect(jsonPath("$.reason").value("INCIDENT_RESOLVED"));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM analysis_jobs WHERE status='SUCCEEDED'",Long.class)).isZero();
 }
}
