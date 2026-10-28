package com.sentrix.core.monitoring;
import com.sentrix.core.environment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class MonitoringTests {
 @Autowired MockMvc mvc; @Autowired EnvironmentRepository env; @Autowired JdbcTemplate db;
 Long id;
 @BeforeEach void setup(){id=env.saveAndFlush(new Environment("monitor-settings","sentrix")).getId();}
 @AfterEach void clean(){db.update("DELETE FROM monitoring_settings WHERE environment_id=?",id);env.deleteById(id);}
 @Test void persistsIntentWithoutClaimingExecution() throws Exception {
  String url="/api/v1/environments/"+id+"/monitoring";
  mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.executionStatus").value("DISABLED"));
  mvc.perform(put(url).contentType("application/json").content("{\"enabled\":true,\"intervalSeconds\":60}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.executionStatus").value("BLOCKED_MODEL_UNAVAILABLE")).andExpect(jsonPath("$.lastSuccessfulAt").isEmpty());
  mvc.perform(get(url)).andExpect(jsonPath("$.enabled").value(true));
  mvc.perform(put(url).contentType("application/json").content("{\"enabled\":false,\"intervalSeconds\":120}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.executionStatus").value("DISABLED"));
 }
 @Test void validatesInputAndEnvironment() throws Exception {
  mvc.perform(put("/api/v1/environments/"+id+"/monitoring").contentType("application/json").content("{\"enabled\":true,\"intervalSeconds\":1}")) .andExpect(status().isBadRequest());
  mvc.perform(get("/api/v1/environments/999999/monitoring")).andExpect(status().isNotFound());
 }
}
