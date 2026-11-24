package com.sentrix.core.analysis;

import com.sentrix.core.environment.Environment;
import com.sentrix.core.environment.EnvironmentRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogSemanticsTests {
    final ObjectMapper mapper = new ObjectMapper();
    final EnvironmentRepository environments = mock(EnvironmentRepository.class);
    final AnalysisModelClient model = mock(AnalysisModelClient.class);
    final LogSemanticsController controller = new LogSemanticsController(environments, model, mapper);

    private void environment() {
        when(environments.findById(1L)).thenReturn(Optional.of(new Environment("test", "sentrix")));
    }

    @Test
    void onlyTheOperatorCanReadSentencesAndNewModelFieldsAreNeverForwarded() {
        environment();
        when(model.logSemantics(eq(1L), eq("sentrix"), any())).thenAnswer(inv -> {
            var raw = mapper.readTree("""
                {"environmentId":1,"namespace":"sentrix","windowSeconds":30,"status":"OK","lines":12,
                 "baseline":{"start":10,"end":20,"lines":32,"sentences":3,"projection":"pca","sensitive":"PRIVATE"},
                 "model":{"name":"sentence-transformers/all-MiniLM-L6-v2","revision":"1110a243fdf4706b3f48f1d95db1a4f5529b4d41"},
                 "novel":{"sentences":2,"lines":5},"diagnostic":"PRIVATE",
                 "services":[{"service":"frontend","stopped":false,"baselineRate":1.3,"currentRate":0.4,"novelLines":5,"novelSentences":2}],
                 "points":[{"service":"frontend","text":"PRIVATE example","count":3,"errors":3,"novel":true,"distance":0.3,"x":3,"y":2,"other":"PRIVATE"}],
                 "baselinePoints":[{"service":"frontend","text":"PRIVATE baseline","count":5,"x":0,"y":1}]}
                """);
            ((tools.jackson.databind.node.ObjectNode) raw).put("windowEnd", inv.<Instant>getArgument(2).toString());
            return raw;
        });
        var summary = controller.summary(1L);
        assertThat(summary.path("visibility").asText()).isEqualTo("public");
        assertThat(summary.path("status").asText()).isEqualTo("OK");
        assertThat(summary.toString()).doesNotContain("PRIVATE");
        assertThat(summary.path("points").get(0).has("text")).isFalse();
        assertThat(summary.path("baselinePoints").get(0).has("text")).isFalse();
        assertThat(summary.path("services").get(0).path("novelLines").asLong()).isEqualTo(5);
        assertThat(controller.operator(1L).path("points").get(0).path("text").asText()).isEqualTo("PRIVATE example");
        assertThat(controller.summary(1L).toString()).doesNotContain("PRIVATE");
    }

    @Test
    void unavailableCannotExposeAnUpstreamMessageAndTheScopeMustMatch() {
        environment();
        when(model.logSemantics(eq(1L), eq("sentrix"), any())).thenAnswer(inv -> mapper.createObjectNode()
                .put("environmentId", 1).put("namespace", "sentrix").put("windowSeconds", 30)
                .put("windowEnd", inv.<Instant>getArgument(2).toString()).put("status","UNAVAILABLE")
                .put("reason","PRIVATE raw line"));
        assertThat(controller.summary(1L).path("reason").asText()).isEqualTo("LOG_SEMANTICS_UNAVAILABLE");
        when(environments.findById(2L)).thenReturn(Optional.of(new Environment("other","second")));
        when(model.logSemantics(eq(2L),eq("second"),any())).thenReturn(mapper.createObjectNode().put("environmentId",1));
        assertThat(controller.operator(2L).path("reason").asText()).isEqualTo("INVALID_MODEL_RESPONSE");
    }
}
