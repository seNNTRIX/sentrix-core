package com.sentrix.core.telemetry;

import com.sentrix.core.datasource.PrometheusClient;
import org.springframework.http.HttpStatus;

enum MetricDefinition {
    cpu_usage_cores("cores", "deployment"), memory_working_set_bytes("bytes", "deployment"),
    request_rate("requests/second", "service_name"), error_ratio("ratio", "service_name"),
    latency_p50_ms("milliseconds", "service_name"), latency_p90_ms("milliseconds", "service_name"),
    latency_p99_ms("milliseconds", "service_name"),
    available_replicas("replicas", "deployment"), desired_replicas("replicas", "deployment"),
    cpu_limit_ratio("ratio", "deployment"), cpu_throttling_ratio("ratio", "deployment");

    final String unit;
    final String serviceLabel;

    MetricDefinition(String unit, String serviceLabel) {
        this.unit = unit;
        this.serviceLabel = serviceLabel;
    }

    static MetricDefinition parse(String name) {
        try { return valueOf(name); }
        catch (IllegalArgumentException exception) {
            throw PrometheusClient.error(HttpStatus.BAD_REQUEST, "INVALID_METRIC", "Unsupported metric name");
        }
    }

    String expression(String namespace, String service, int stepSeconds) {
        // Both inputs are validated Kubernetes names, never user-provided PromQL.
        String ns = "namespace=\"" + namespace + "\"";
        if (this == available_replicas || this == desired_replicas) {
            String metric = this == available_replicas ? "kube_deployment_status_replicas_available" : "kube_deployment_spec_replicas";
            String selector = metric + "{" + ns + ",deployment=\"" + service + "\"}";
            String recent = selector + " and (time() - timestamp(" + selector + ") < " + Math.min(stepSeconds, 60) + ")";
            // A stale or conflicting scrape cannot establish the replica count.
            return "min by (deployment) (" + recent + ") == max by (deployment) (" + recent + ")";
        }
        if (serviceLabel.equals("deployment")) {
            String podOwners = "max by (namespace,pod,replicaset) (label_replace(kube_pod_owner{" + ns
                    + ",owner_kind=\"ReplicaSet\",owner_is_controller=\"true\"},\"replicaset\",\"$1\",\"owner_name\",\"(.+)\"))";
            String deployments = "max by (namespace,replicaset,deployment) (label_replace(kube_replicaset_owner{" + ns
                    + ",owner_kind=\"Deployment\",owner_is_controller=\"true\",owner_name=\"" + service
                    + "\"},\"deployment\",\"$1\",\"owner_name\",\"(.+)\"))";
            String owners = podOwners + " * on(namespace,replicaset) group_left(deployment) " + deployments;
            String containers = "{" + ns + ",container!=\"\",container!=\"POD\"}";
            if (this == cpu_usage_cores || this == memory_working_set_bytes) {
                String metric = this == cpu_usage_cores
                        ? "rate(container_cpu_usage_seconds_total" + containers + "[1m])"
                        : "container_memory_working_set_bytes" + containers;
                return deploymentSum(metric, owners);
            }
            String measured = "{" + ns + ",pod!=\"\",container!=\"\",container!=\"POD\"}";
            if (this == cpu_limit_ratio) {
                String used = deploymentSum(freshRate("container_cpu_usage_seconds_total" + measured, "irate"), owners);
                String limit = deploymentSum("kube_pod_container_resource_limits{" + ns + ",resource=\"cpu\"}", owners);
                return used + " / (" + limit + " > 0)";
            }
            String throttled = deploymentSum(freshRate("container_cpu_cfs_throttled_periods_total" + measured, "rate"), owners);
            String periods = deploymentSum(freshRate("container_cpu_cfs_periods_total" + measured, "rate"), owners);
            return throttled + " / (" + periods + " > 0)";
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

    private static String freshRate(String selector, String function) {
        // Match the model's short CPU window and raw cAdvisor freshness requirement.
        return "(" + function + "(" + selector + "[15s]) and (time() - timestamp(" + selector + ") < 5))";
    }

    private static String deploymentSum(String metric, String owners) {
        return "sum by (deployment) ((sum by (namespace,pod) (max by (namespace,pod,container) (" + metric
                + "))) * on(namespace,pod) group_left(deployment) (" + owners + "))";
    }
}
