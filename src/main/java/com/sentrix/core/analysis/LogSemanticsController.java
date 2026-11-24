package com.sentrix.core.analysis;

import com.sentrix.core.environment.EnvironmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.sentrix.core.datasource.PrometheusClient.error;

@Tag(name="06. 로그 분석")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/log-semantics")
class LogSemanticsController {
    private final EnvironmentRepository environments;
    private final AnalysisModelClient model;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    LogSemanticsController(EnvironmentRepository environments, AnalysisModelClient model, ObjectMapper mapper) {
        this.environments = environments;
        this.model = model;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary="운영자용 최근 30초 로그 의미 공간", description="모델이 정제한 문장, 비교 좌표와 서비스별 건수를 조회합니다.")
    JsonNode operator(@PathVariable Long environmentId) {
        return current(environmentId, false);
    }

    @GetMapping("/summary")
    @Operation(summary="공개 전시용 최근 30초 로그 의미 집계", description="문장 내용을 제외한 빈도, 거리 및 위치만 제공합니다.")
    JsonNode summary(@PathVariable Long environmentId) {
        return current(environmentId, true);
    }

    private static class Snapshot {
        Instant windowEnd;
        ObjectNode operator;
        ObjectNode summary;
    }

    private JsonNode current(long id, boolean publicView) {
        var env = environments.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Environment not found"));
        var snapshot = snapshots.computeIfAbsent(id, ignored -> new Snapshot());
        synchronized (snapshot) {
            long now = Instant.now().getEpochSecond();
            var end = Instant.ofEpochSecond(now - now % 10);
            if (!end.equals(snapshot.windowEnd)) {
                ObjectNode operator;
                try {
                    JsonNode source = model.logSemantics(id, env.getNamespace(), end);
                    operator = normalized(source, id, env.getNamespace(), end);
                } catch (AnalysisModelClient.AnalysisFailure failure) {
                    operator = unavailable(id, env.getNamespace(), end, failure.code());
                } catch (RuntimeException invalidResponse) {
                    operator = unavailable(id, env.getNamespace(), end, "INVALID_MODEL_RESPONSE");
                }
                var summary = operator.deepCopy();
                for (String key : List.of("points", "baselinePoints")) {
                    for (JsonNode point : summary.path(key)) ((ObjectNode) point).remove("text");
                }
                summary.put("visibility", "public");
                operator.put("visibility", "operator");
                snapshot.operator = operator;
                snapshot.summary = summary;
                snapshot.windowEnd = end;
            }
            return publicView ? snapshot.summary : snapshot.operator;
        }
    }

    private ObjectNode envelope(long id, String namespace, Instant end) {
        var out = mapper.createObjectNode();
        out.put("environmentId", id);
        out.put("namespace", namespace);
        out.put("windowEnd", end.toString());
        out.put("windowSeconds", 30);
        return out;
    }

    private ObjectNode unavailable(long id, String namespace, Instant end, String reason) {
        var out = envelope(id, namespace, end);
        out.put("status", "UNAVAILABLE");
        out.put("reason", reason);
        return out;
    }

    private ObjectNode normalized(JsonNode src, long id, String namespace, Instant end) {
        require(src != null && src.isObject() && src.path("environmentId").isIntegralNumber()
                && src.path("environmentId").asLong() == id && namespace.equals(src.path("namespace").asText())
                && end.equals(Instant.parse(src.path("windowEnd").asText())) && src.path("windowSeconds").asInt() == 30);
        if ("UNAVAILABLE".equals(src.path("status").asText())) {
            String reason = src.path("reason").asText();
            return unavailable(id, namespace, end, KNOWN_REASONS.contains(reason) ? reason : "LOG_SEMANTICS_UNAVAILABLE");
        }
        require("OK".equals(src.path("status").asText()));
        var out = envelope(id, namespace, end);
        out.put("status", "OK");
        numbers(src, out, "lines");
        var baseline = out.putObject("baseline");
        numbers(src.path("baseline"), baseline, "start", "end", "lines", "sentences");
        String projection = src.path("baseline").path("projection").asText();
        require(List.of("pca","umap").contains(projection));
        baseline.put("projection", projection);
        numbers(src.path("novel"), out.putObject("novel"), "sentences", "lines");
        var encoder = src.path("model");
        String name = encoder.path("name").asText();
        String revision = encoder.path("revision").asText();
        require(name.equals("sentence-transformers/all-MiniLM-L6-v2") && revision.matches("[0-9a-f]{40}"));
        var modelInfo = out.putObject("model");
        modelInfo.put("name", name);
        modelInfo.put("revision", revision);

        for (String list : List.of("services","points","baselinePoints")) {
            var rows = src.path(list);
            require(rows.isArray() && rows.size() <= 400);
            var target = out.putArray(list);
            for (JsonNode r : rows) {
                var item = target.addObject();
                String service = r.path("service").asText();
                require(service.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"));
                item.put("service", service);
                if (list.equals("services")) {
                    numbers(r, item, "baselineRate", "currentRate", "novelSentences", "novelLines");
                    flag(r, item, "stopped");
                } else {
                    numbers(r, item, "count", "x", "y");
                    require(r.path("text").isString());
                    item.put("text", r.path("text").asText());
                    if (list.equals("points")) {
                        numbers(r, item, "errors");
                        flag(r, item, "novel");
                        if (r.path("distance").isNull()) item.putNull("distance");
                        else numbers(r, item, "distance");
                    }
                }
            }
        }
        return out;
    }

    private static final List<String> KNOWN_REASONS = List.of("TELEMETRY_NOT_CONFIGURED","TELEMETRY_ENVIRONMENT_NOT_CONFIGURED",
            "LOG_SEMANTICS_NOT_INSTALLED","BASELINE_LOGS_INSUFFICIENT","BASELINE_OVERLAPS_WINDOW",
            "LOG_QUERY_INCOMPLETE","LOG_SCOPE_MISMATCH","LOG_TIME_MISMATCH","LOG_QUERY_LIMIT","INVALID_TELEMETRY_RESPONSE",
            "LOG_SEMANTICS_LOADING","LOG_MODEL_NOT_AVAILABLE","LOG_MODEL_INVALID","TELEMETRY_BUDGET_EXCEEDED",
            "TELEMETRY_UPSTREAM_ERROR","TELEMETRY_QUERY_LIMIT");

    private static void numbers(JsonNode src, ObjectNode out, String... fields) {
        for (String field : fields) {
            var value = src.path(field);
            require(value.isNumber() && Double.isFinite(value.asDouble()));
            out.set(field, value);
        }
    }

    private static void flag(JsonNode src, ObjectNode out, String field) {
        require(src.path(field).isBoolean());
        out.put(field, src.path(field).asBoolean());
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid log semantics response");
    }
}
