package com.sentrix.core.telemetry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.sentrix.core.datasource.PrometheusClient;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name="03. 서비스와 메트릭")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/services")
class DeploymentStatusController {
    private static final int MAX_AGE_SECONDS = 60;
    private static final Map<String, String> METRICS = Map.of(
            "desiredReplicas", "kube_deployment_spec_replicas",
            "replicas", "kube_deployment_status_replicas",
            "readyReplicas", "kube_deployment_status_replicas_ready",
            "availableReplicas", "kube_deployment_status_replicas_available");
    private final PrometheusClient prometheus;

    DeploymentStatusController(PrometheusClient prometheus) { this.prometheus = prometheus; }

    static String expression(String namespace, String service) {
        var parts = new ArrayList<String>();
        METRICS.forEach((field, metric) -> {
            String selector = metric + "{namespace=\"" + namespace + "\",deployment=\"" + service + "\"}";
            for (String stat : List.of("min", "max", "observedAt")) {
                // timestamp() must see the raw selector, not an aggregation's evaluation timestamp.
                String aggregate = stat.equals("observedAt") ? "min(timestamp(" + selector + "))"
                        : stat + "(" + selector + ")";
                parts.add("label_replace(label_replace(" + aggregate + ",\"field\",\"" + field
                        + "\",\"\",\"\"),\"stat\",\"" + stat + "\",\"\",\"\")");
            }
        });
        return String.join(" or ", parts);
    }

    @Operation(summary="서비스 준비 상태 조회", description="Deployment의 목표·현재·준비·가용 Pod 수를 조회합니다. 60초 초과 지표는 제외하며 미관측 서비스는 UNKNOWN일 수 있습니다. READY는 Kubernetes 준비 조건이며 ML 장애 판정이나 요청 성공 보장이 아닙니다.")
    @GetMapping("/{service}/status")
    DeploymentStatus status(@PathVariable Long environmentId, @PathVariable String service) {
        if (!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
            throw PrometheusClient.error(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "Invalid service name");
        }
        var connection = prometheus.connection(environmentId);
        Instant now = Instant.now();
        var response = prometheus.query(connection,
                Map.of("query", expression(connection.namespace(), service), "time", now.toString()), false);
        var samples = new HashMap<String, Map<String, Double>>();
        for (var series : response.series()) {
            String field = series.path("metric").path("field").asText();
            String stat = series.path("metric").path("stat").asText();
            var pair = series.path("value");
            if (!METRICS.containsKey(field) || !Set.of("min", "max", "observedAt").contains(stat)
                    || !pair.isArray() || pair.size() != 2 || !pair.get(0).isNumber() || !pair.get(1).isTextual()) {
                throw PrometheusClient.invalidResponse();
            }
            double value;
            try { value = Double.parseDouble(pair.get(1).asText()); }
            catch (NumberFormatException exception) { throw PrometheusClient.invalidResponse(); }
            if (!Double.isFinite(value) || value < 0 || (stat.equals("observedAt")
                    ? value > now.toEpochMilli() / 1000.0 + 5
                    : value > Integer.MAX_VALUE || value != Math.rint(value))) throw PrometheusClient.invalidResponse();
            if (samples.computeIfAbsent(field, ignored -> new HashMap<>()).put(stat, value) != null) {
                throw PrometheusClient.invalidResponse();
            }
        }
        var warnings = new TreeSet<String>();
        if (response.hasWarnings()) warnings.add("UPSTREAM_WARNING");
        var counts = new HashMap<String, Integer>();
        Instant observedAt = null;
        for (String field : METRICS.keySet()) {
            var sample = samples.getOrDefault(field, Map.of());
            if (sample.size() != 3) { warnings.add("MISSING_METRICS"); continue; }
            Instant time = Instant.ofEpochMilli((long) (sample.get("observedAt") * 1000));
            if (observedAt == null || time.isBefore(observedAt)) observedAt = time;
            if (time.isBefore(now.minusSeconds(MAX_AGE_SECONDS))) { warnings.add("STALE_METRICS"); continue; }
            if (!sample.get("min").equals(sample.get("max"))) { warnings.add("INCONSISTENT_METRICS"); continue; }
            counts.put(field, sample.get("min").intValue());
        }
        Integer desired = counts.get("desiredReplicas"), replicas = counts.get("replicas"),
                ready = counts.get("readyReplicas"), available = counts.get("availableReplicas");
        String status = "UNKNOWN";
        if (counts.size() == 4) {
            if (ready > replicas || available > ready) warnings.add("INCONSISTENT_METRICS");
            if (warnings.isEmpty()) {
                status = desired == 0 && replicas == 0 ? "SCALED_DOWN"
                        : desired > 0 && ready >= desired && available >= desired ? "READY" : "NOT_READY";
            }
        }
        return new DeploymentStatus(environmentId, connection.namespace(), service, now, observedAt,
                MAX_AGE_SECONDS, status, desired, replicas, ready, available, List.copyOf(warnings));
    }

    record DeploymentStatus(Long environmentId, String namespace, String service, Instant queriedAt,
                            Instant observedAt, int maxAgeSeconds, String status, Integer desiredReplicas,
                            Integer replicas, Integer readyReplicas, Integer availableReplicas, List<String> warnings) {}
}
