package com.sentrix.core.telemetry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.sentrix.core.datasource.PrometheusClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@Tag(name="03. 서비스와 메트릭")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}")
class TelemetryController {
    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");
    private final PrometheusClient prometheus;

    TelemetryController(PrometheusClient prometheus) { this.prometheus = prometheus; }

    @Operation(summary="서비스 목록 조회", description="현재 namespace의 Deployment 목록입니다. 관측 도구도 포함될 수 있으며 서비스 건강 상태나 trace 계측 여부를 보장하지 않습니다.")
    @GetMapping("/services")
    ServiceList services(@PathVariable Long environmentId) {
        var connection = prometheus.connection(environmentId);
        Instant queriedAt = Instant.now();
        String query = "max by (deployment) (kube_deployment_created{namespace=\"" + connection.namespace() + "\"})";
        var response = prometheus.query(connection, Map.of("query", query, "time", queriedAt.toString()), false);
        if (response.series().size() > 500) throw PrometheusClient.error(HttpStatus.BAD_GATEWAY,
                "TOO_MANY_SERVICES", "Service inventory exceeds the 500 deployment limit");
        var services = new TreeMap<String, Service>();
        for (JsonNode series : response.series()) {
            String name = series.path("metric").path("deployment").asText();
            if (!NAME.matcher(name).matches()) throw PrometheusClient.invalidResponse();
            var value = pair(series.path("value"));
            Double created = number(value.get(1));
            if (created == null || created < 0 || created > queriedAt.getEpochSecond() + 60) throw PrometheusClient.invalidResponse();
            if (services.put(name, new Service(name, "Deployment", epoch(value.get(1)))) != null) throw PrometheusClient.invalidResponse();
        }
        return new ServiceList(environmentId, connection.namespace(), queriedAt,
                services.isEmpty() ? "NO_DATA" : response.hasWarnings() ? "PARTIAL" : "AVAILABLE",
                response.hasWarnings() ? List.of("UPSTREAM_WARNING") : List.of(), List.copyOf(services.values()));
    }

    @Operation(summary="서비스 메트릭 시계열 조회", description="service, metric, from, to는 필수입니다. UTC ISO 8601 초 단위 시각, from 포함·to 제외, 최대 24시간입니다. stepSeconds는 5~3600(기본 30), 최대 1440개 지점입니다. 실제 0과 누락 null을 구분합니다. CPU는 cores, 메모리는 bytes, 오류율은 0~1 비율, 지연은 ms입니다. AVAILABLE은 서비스 정상 판정이 아닙니다.")
    @GetMapping("/metrics")
    MetricSeries metrics(@PathVariable Long environmentId, @RequestParam String service,
                         @Parameter(schema=@Schema(allowableValues={"cpu_usage_cores","memory_working_set_bytes","request_rate","error_ratio","latency_p50_ms","latency_p90_ms","latency_p99_ms"})) @RequestParam String metric, @RequestParam Instant from, @RequestParam Instant to,
                         @RequestParam(defaultValue = "30") int stepSeconds) {
        if (!NAME.matcher(service).matches()) throw badRequest("Invalid service name");
        var definition = MetricDefinition.parse(metric);
        if (from.getNano() != 0 || to.getNano() != 0 || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0
                || stepSeconds < 5 || stepSeconds > 3600) {
            throw badRequest("Use whole-second timestamps, from < to, at most 24 hours, and stepSeconds 5..3600");
        }
        long count = (Duration.between(from, to).getSeconds() - 1) / stepSeconds + 1;
        if (count > 1440) throw badRequest("At most 1440 points; increase stepSeconds or shorten the range");
        var connection = prometheus.connection(environmentId);
        Instant last = from.plusSeconds((count - 1) * stepSeconds);
        var response = prometheus.query(connection, Map.of("query", definition.expression(connection.namespace(), service),
                "start", from.toString(), "end", last.toString(), "step", String.valueOf(stepSeconds)), true);
        if (response.series().size() > 1) throw PrometheusClient.invalidResponse();
        var values = new HashMap<Instant, Double>();
        var warnings = new LinkedHashSet<String>();
        if (response.hasWarnings()) warnings.add("UPSTREAM_WARNING");
        for (JsonNode series : response.series()) {
            String label = definition == MetricDefinition.cpu_usage_cores || definition == MetricDefinition.memory_working_set_bytes
                    ? "deployment" : "service_name";
            if (!service.equals(series.path("metric").path(label).asText()) || !series.path("values").isArray()) {
                throw PrometheusClient.invalidResponse();
            }
            if (series.path("values").size() > count) throw PrometheusClient.invalidResponse();
            for (JsonNode sample : series.path("values")) {
                var pair = pair(sample);
                Instant timestamp = epoch(pair.get(0));
                if (timestamp.getNano() != 0 || timestamp.isBefore(from) || timestamp.isAfter(last)
                        || (timestamp.getEpochSecond() - from.getEpochSecond()) % stepSeconds != 0
                        || values.containsKey(timestamp)) throw PrometheusClient.invalidResponse();
                Double value = number(pair.get(1));
                if (value == null) warnings.add("NON_FINITE_VALUES");
                values.put(timestamp, value);
            }
        }
        var points = new ArrayList<Point>();
        int available = 0;
        for (long i = 0; i < count; i++) {
            Instant timestamp = from.plusSeconds(i * stepSeconds);
            Double value = values.get(timestamp);
            if (value != null) available++;
            points.add(new Point(timestamp, value));
        }
        String dataStatus = available == 0 ? "NO_DATA" : available < count || !warnings.isEmpty() ? "PARTIAL" : "AVAILABLE";
        return new MetricSeries(environmentId, connection.namespace(), service, metric, definition.unit, from, to,
                stepSeconds, dataStatus, List.copyOf(warnings), points);
    }

    private static JsonNode pair(JsonNode value) {
        if (!value.isArray() || value.size() != 2 || !value.get(0).isNumber() || !value.get(1).isTextual()) {
            throw PrometheusClient.invalidResponse();
        }
        return value;
    }

    private static Instant epoch(JsonNode value) {
        try {
            var seconds = new BigDecimal(value.asText());
            long whole = seconds.longValueExact();
            return Instant.ofEpochSecond(whole);
        } catch (RuntimeException exception) { throw PrometheusClient.invalidResponse(); }
    }

    private static Double number(JsonNode value) {
        String text = value.asText();
        if (Set.of("NaN", "+Inf", "-Inf").contains(text)) return null;
        try {
            double number = Double.parseDouble(text);
            if (!Double.isFinite(number)) throw PrometheusClient.invalidResponse();
            return number;
        } catch (NumberFormatException exception) { throw PrometheusClient.invalidResponse(); }
    }

    private static org.springframework.web.server.ResponseStatusException badRequest(String message) {
        return PrometheusClient.error(HttpStatus.BAD_REQUEST, "INVALID_QUERY", message);
    }

    record Service(String name, String kind, Instant createdAt) {}
    record ServiceList(Long environmentId, String namespace, Instant queriedAt, String dataStatus,
                       List<String> warnings, List<Service> items) {}
    record Point(Instant timestamp, Double value) {}
    record MetricSeries(Long environmentId, String namespace, String service, String metric, String unit,
                        Instant from, Instant to, int stepSeconds, String dataStatus, List<String> warnings, List<Point> points) {}
}
