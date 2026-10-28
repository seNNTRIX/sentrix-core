package com.sentrix.core.telemetry;

import com.sentrix.core.datasource.PrometheusClient;
import org.springframework.http.HttpStatus;

enum MetricDefinition {
    cpu_usage_cores("cores"), memory_working_set_bytes("bytes"), request_rate("requests/second"),
    error_ratio("ratio"), latency_p50_ms("milliseconds"), latency_p90_ms("milliseconds"), latency_p99_ms("milliseconds");

    final String unit;
    MetricDefinition(String unit) { this.unit = unit; }

    static MetricDefinition parse(String name) {
        try { return valueOf(name); }
        catch (IllegalArgumentException exception) {
            throw PrometheusClient.error(HttpStatus.BAD_REQUEST, "INVALID_METRIC", "Unsupported metric name");
        }
    }

    String expression(String namespace, String service) {
        // Both inputs are validated Kubernetes names, never user-provided PromQL.
        String ns = "namespace=\"" + namespace + "\"";
        if (this == cpu_usage_cores || this == memory_working_set_bytes) {
            String metric = this == cpu_usage_cores
                    ? "rate(container_cpu_usage_seconds_total{" + ns + ",container!=\"\",container!=\"POD\"}[1m])"
                    : "container_memory_working_set_bytes{" + ns + ",container!=\"\",container!=\"POD\"}";
            String podOwners = "max by (namespace,pod,replicaset) (label_replace(kube_pod_owner{" + ns
                    + ",owner_kind=\"ReplicaSet\",owner_is_controller=\"true\"},\"replicaset\",\"$1\",\"owner_name\",\"(.+)\"))";
            String deployments = "max by (namespace,replicaset,deployment) (label_replace(kube_replicaset_owner{" + ns
                    + ",owner_kind=\"Deployment\",owner_is_controller=\"true\",owner_name=\"" + service
                    + "\"},\"deployment\",\"$1\",\"owner_name\",\"(.+)\"))";
            return "sum by (deployment) ((sum by (namespace,pod) (max by (namespace,pod,container) (" + metric
                    + "))) * on(namespace,pod) group_left(deployment) (" + podOwners
                    + " * on(namespace,replicaset) group_left(deployment) " + deployments + "))";
        }
        String labels = "service_namespace=\"" + namespace + "\",service_name=\"" + service + "\",span_kind=\"SPAN_KIND_SERVER\"";
        String calls = "sum by (service_name) (rate(traces_span_metrics_calls_total{" + labels + "}[1m]))";
        if (this == request_rate) return calls;
        if (this == error_ratio) {
            String errors = "sum by (service_name) (rate(traces_span_metrics_calls_total{" + labels
                    + ",status_code=\"STATUS_CODE_ERROR\"}[1m]))";
            // Fill absent error series only when a denominator exists; 0/0 remains unknown.
            return "(" + errors + " or (0 * " + calls + ")) / " + calls;
        }
        String quantile = switch (this) {
            case latency_p50_ms -> "0.50";
            case latency_p90_ms -> "0.90";
            default -> "0.99";
        };
        return "histogram_quantile(" + quantile + ", sum by (service_name,le) (rate(traces_span_metrics_duration_milliseconds_bucket{"
                + labels + "}[1m])))";
    }
}
