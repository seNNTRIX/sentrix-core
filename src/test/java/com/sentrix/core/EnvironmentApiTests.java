package com.sentrix.core;

import com.sentrix.core.environment.EnvironmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnvironmentApiTests {
    @Autowired MockMvc mvc;
    @Autowired EnvironmentRepository repository;

    @BeforeEach
    void clean() { repository.deleteAll(); }

    @Test
    void registerReadAndPageEnvironment() throws Exception {
        var result = mvc.perform(post("/api/v1/environments").contentType("application/json")
                        .content("""
                                {"name":" Online Boutique ","namespace":"sentrix"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Online Boutique"))
                .andExpect(jsonPath("$.createdAt").exists()).andReturn();
        mvc.perform(get(result.getResponse().getHeader("Location")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.namespace").value("sentrix"));
        mvc.perform(get("/api/v1/environments?size=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Online Boutique"));
        mvc.perform(get("/api/v1/environments?page=1&size=1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void rejectsDuplicateInvalidAndMissingEnvironments() throws Exception {
        var body = "{\"name\":\"Boutique\",\"namespace\":\"sentrix\"}";
        mvc.perform(post("/api/v1/environments").contentType("application/json").content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/environments").contentType("application/json").content(body))
                .andExpect(status().isConflict());
        for (String invalid : new String[]{"{}", """
                {"name":" ","namespace":"sentrix"}
                """,
                "{\"name\":\"x\",\"namespace\":\"Invalid_Name\"}",
                "{\"name\":\"x\",\"namespace\":\"-invalid\"}", "not-json"}) {
            mvc.perform(post("/api/v1/environments").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/environments/999999")).andExpect(status().isNotFound());
        for (String query : new String[]{"page=-1", "size=0", "size=51", "page=x"}) {
            mvc.perform(get("/api/v1/environments?" + query)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
