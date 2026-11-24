package com.sentrix.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void documentsImplementedOperationsAndTheirContracts() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var doc = mapper.readTree(result.getResponse().getContentAsString());
        var paths = doc.path("paths");
        assertThat(doc.path("components").path("schemas").path("ApiProblem").path("properties").has("status")).isTrue();
        int count = 0;
        for (var path : paths) {
            for (var operation : path) {
                assertThat(operation.path("summary").asText()).isNotBlank();
                count++;
            }
        }
        assertThat(count).isEqualTo(34);
        assertThat(paths.has("/api/v1/environments/{environmentId}/log-semantics")).isTrue();
        assertThat(paths.has("/api/v1/environments/{environmentId}/log-semantics/summary")).isTrue();
        var rcaParameters = paths.path("/api/v1/environments/{environmentId}/incidents/{incidentId}/rca").path("get").path("parameters");
        var at = rcaParameters.valueStream().filter(p -> p.path("name").asText().equals("at")).findFirst().orElseThrow();
        assertThat(at.path("schema").path("enum").valueStream().map(n -> n.asText()).toList())
                .containsExactlyInAnyOrder("first", "latest");
        assertThat(at.path("schema").path("default").asText()).isEqualTo("latest");
        assertThat(paths.has("/api/v1/environments/{environmentId}/anomaly-scores")).isTrue();
        assertThat(paths.path("/api/v1/environments/{environmentId}/incidents/{incidentId}/rca-jobs/{jobId}/result").path("get").path("responses").has("409")).isTrue();
        var volume = paths.path("/api/v1/environments/{environmentId}/logs/volume").path("get");
        String volumeRef = volume.path("responses").path("200").path("content").path("*/*").path("schema").path("$ref").asText();
        assertThat(volumeRef).isEqualTo("#/components/schemas/LogVolumeResult");
        var volumeProperties = doc.path("components").path("schemas").path("LogVolumeResult").path("properties");
        assertThat(volumeProperties.has("buckets")).isTrue();
        assertThat(volumeProperties.has("pod")).isFalse();
        assertThat(volume.path("responses").has("504")).isTrue();
        assertThat(paths.has("/api/v1/environments/{environmentId}/collection-status")).isTrue();
        assertThat(doc.path("servers").get(0).path("url").asText()).isEqualTo("/");
        assertThat(paths.has("/api/v1/environments/{environmentId}/traces/{traceId}")).isTrue();
        assertThat(paths.has("/actuator/health")).isTrue();
        assertThat(paths.has("/actuator/health/{*path}")).isFalse();
        assertThat(paths.has("/api/v1/environments/{id}/incidents")).isFalse();
        var create = paths.path("/api/v1/environments").path("post");
        assertThat(create.path("responses").has("201")).isTrue();
        assertThat(create.path("responses").has("200")).isFalse();
        assertThat(create.path("requestBody").path("required").asBoolean()).isTrue();
        var metrics = paths.path("/api/v1/environments/{environmentId}/metrics").path("get");
        assertThat(metrics.path("responses").path("504").path("content").has("application/problem+json")).isTrue();
        var metricParameter = metrics.path("parameters").valueStream()
                .filter(p -> p.path("name").asText().equals("metric")).findFirst().orElseThrow();
        assertThat(metricParameter.path("required").asBoolean()).isTrue();
        assertThat(metricParameter.path("schema").path("enum").valueStream().map(n -> n.asText()).toList())
                .containsExactlyInAnyOrder("cpu_usage_cores", "memory_working_set_bytes", "request_rate", "error_ratio",
                        "latency_p50_ms", "latency_p90_ms", "latency_p99_ms", "available_replicas", "desired_replicas",
                        "cpu_limit_ratio", "cpu_throttling_ratio");
        assertThat(doc.path("components").path("schemas").path("CreateEnvironment").path("properties").has("namespace")).isTrue();
    }

    @Test
    void servesLocalSwaggerUiAndDisablesExternalValidation() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"))
                .andExpect(jsonPath("$.validatorUrl").value(""));
    }
}
