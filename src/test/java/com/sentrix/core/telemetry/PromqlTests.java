package com.sentrix.core.telemetry;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromqlTests {
    @Test
    void exportsProductionExpressionsForPromtoolSemanticChecks() throws Exception {
        String fixtures = """
                rule_files: []
                evaluation_interval: 30s
                tests:
                  - interval: 30s
                    input_series:
                      - series: 'container_cpu_usage_seconds_total{namespace="sentrix",pod="p1",container="app",job="a"}'
                        values: '0+30x5'
                      - series: 'container_cpu_usage_seconds_total{namespace="sentrix",pod="p1",container="app",job="duplicate"}'
                        values: '0+30x5'
                      - series: 'container_cpu_usage_seconds_total{namespace="sentrix",pod="p2",container="app"}'
                        values: '0+60x5'
                      - series: 'container_cpu_usage_seconds_total{namespace="sentrix",pod="p3",container="app"}'
                        values: '0+300x5'
                      - series: 'container_cpu_usage_seconds_total{namespace="other",pod="p1",container="app"}'
                        values: '0+3000x5'
                      - series: 'container_memory_working_set_bytes{namespace="sentrix",pod="p1",container="app",job="a"}'
                        values: '100+0x5'
                      - series: 'container_memory_working_set_bytes{namespace="sentrix",pod="p1",container="app",job="duplicate"}'
                        values: '100+0x5'
                      - series: 'container_memory_working_set_bytes{namespace="sentrix",pod="p2",container="app"}'
                        values: '200+0x5'
                      - series: 'container_memory_working_set_bytes{namespace="sentrix",pod="p3",container="app"}'
                        values: '900+0x5'
                      - series: 'kube_pod_owner{namespace="sentrix",pod="p1",owner_kind="ReplicaSet",owner_name="front-old",owner_is_controller="true"}'
                        values: '1+0x5'
                      - series: 'kube_pod_owner{namespace="sentrix",pod="p2",owner_kind="ReplicaSet",owner_name="front-new",owner_is_controller="true"}'
                        values: '1+0x5'
                      - series: 'kube_pod_owner{namespace="sentrix",pod="p3",owner_kind="ReplicaSet",owner_name="front-v2",owner_is_controller="true"}'
                        values: '1+0x5'
                      - series: 'kube_replicaset_owner{namespace="sentrix",replicaset="front-old",owner_kind="Deployment",owner_name="frontend",owner_is_controller="true"}'
                        values: '1+0x5'
                      - series: 'kube_replicaset_owner{namespace="sentrix",replicaset="front-new",owner_kind="Deployment",owner_name="frontend",owner_is_controller="true"}'
                        values: '1+0x5'
                      - series: 'kube_replicaset_owner{namespace="sentrix",replicaset="front-v2",owner_kind="Deployment",owner_name="frontend-v2",owner_is_controller="true"}'
                        values: '1+0x5'
                      - series: 'traces_span_metrics_calls_total{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_SERVER",status_code="STATUS_CODE_OK"}'
                        values: '0+90x5'
                      - series: 'traces_span_metrics_calls_total{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_SERVER",status_code="STATUS_CODE_ERROR"}'
                        values: '0+30x5'
                      - series: 'traces_span_metrics_calls_total{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_CLIENT",status_code="STATUS_CODE_OK"}'
                        values: '0+300x5'
                      - series: 'traces_span_metrics_calls_total{service_namespace="other",service_name="frontend",span_kind="SPAN_KIND_SERVER",status_code="STATUS_CODE_OK"}'
                        values: '0+3000x5'
                      - series: 'traces_span_metrics_calls_total{service_namespace="sentrix",service_name="clean",span_kind="SPAN_KIND_SERVER",status_code="STATUS_CODE_OK"}'
                        values: '0+30x5'
                      - series: 'traces_span_metrics_calls_total{service_namespace="sentrix",service_name="idle",span_kind="SPAN_KIND_SERVER",status_code="STATUS_CODE_OK"}'
                        values: '0+0x5'
                      - series: 'traces_span_metrics_duration_milliseconds_bucket{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_SERVER",le="100"}'
                        values: '0+60x5'
                      - series: 'traces_span_metrics_duration_milliseconds_bucket{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_SERVER",le="200"}'
                        values: '0+90x5'
                      - series: 'traces_span_metrics_duration_milliseconds_bucket{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_SERVER",le="500"}'
                        values: '0+120x5'
                      - series: 'traces_span_metrics_duration_milliseconds_bucket{service_namespace="sentrix",service_name="frontend",span_kind="SPAN_KIND_SERVER",le="+Inf"}'
                        values: '0+120x5'
                    promql_expr_test:
                """;
        var yaml = new StringBuilder(fixtures);
        String[] expected = {"3", "300", "4", "0.25", "100", "380", "488"};
        int index = 0;
        for (var definition : java.util.List.of(MetricDefinition.cpu_usage_cores, MetricDefinition.memory_working_set_bytes,
                MetricDefinition.request_rate, MetricDefinition.error_ratio, MetricDefinition.latency_p50_ms,
                MetricDefinition.latency_p90_ms, MetricDefinition.latency_p99_ms)) {
            String label = definition.serviceLabel;
            yaml.append(check(definition, "frontend", "{" + label + "=\"frontend\"}", expected[index++]));
        }
        yaml.append(check(MetricDefinition.error_ratio, "clean", "{service_name=\"clean\"}", "0"));
        yaml.append(check(MetricDefinition.error_ratio, "idle", "{service_name=\"idle\"}", ".nan"));
        yaml.append(check(MetricDefinition.request_rate, "missing", null, null));
        yaml.append(check(MetricDefinition.cpu_usage_cores, "missing", null, null));
        // Raw samples stopped at t=0. Instant query evaluation at t=120 must not
        // turn those samples into fresh data. Duplicate scrapes must not add Pods.
        yaml.append("  - interval: 30s\n    input_series:\n");
        String[] replicaMetrics = {"spec_replicas", "status_replicas", "status_replicas_ready", "status_replicas_available"};
        String[] fields = {"desiredReplicas", "replicas", "readyReplicas", "availableReplicas"};
        for (String name : replicaMetrics) {
            for (String job : new String[]{"a", "duplicate"}) {
                yaml.append("      - series: 'kube_deployment_").append(name)
                        .append("{namespace=\"sentrix\",deployment=\"frontend\",job=\"").append(job)
                        .append("\"}'\n        values: '2'\n");
            }
            yaml.append("      - series: 'kube_deployment_").append(name)
                    .append("{namespace=\"other\",deployment=\"frontend\"}'\n        values: '99'\n");
            yaml.append("      - series: 'kube_deployment_").append(name)
                    .append("{namespace=\"sentrix\",deployment=\"frontend-v2\"}'\n        values: '99'\n");
        }
        yaml.append("    promql_expr_test:\n      - expr: '")
                .append(DeploymentStatusController.expression("sentrix", "frontend"))
                .append("'\n        eval_time: 2m\n        exp_samples:\n");
        for (String field : fields) {
            for (String stat : new String[]{"min", "max", "observedAt"}) {
                yaml.append("          - labels: '{field=\"").append(field).append("\",stat=\"").append(stat)
                        .append("\"}'\n            value: ").append(stat.equals("observedAt") ? 0 : 2).append("\n");
            }
        }
        for (var replica : java.util.List.of(MetricDefinition.available_replicas, MetricDefinition.desired_replicas)) {
            yaml.append(check(replica, "frontend", null, null)); // both scrapes over a minute old
        }
        yaml.append("""
                  - interval: 5s
                    input_series:
                      - series: 'kube_deployment_status_replicas_available{namespace="sentrix",deployment="frontend"}'
                        values: '0+0x24'
                      - series: 'kube_deployment_spec_replicas{namespace="sentrix",deployment="frontend"}'
                        values: '1+0x24'
                      - series: 'kube_deployment_spec_replicas{namespace="sentrix",deployment="conflict",job="a"}'
                        values: '2+0x24'
                      - series: 'kube_deployment_spec_replicas{namespace="sentrix",deployment="conflict",job="b"}'
                        values: '1+0x24'
                      - series: 'kube_pod_owner{namespace="sentrix",pod="p",owner_kind="ReplicaSet",owner_name="front-v",owner_is_controller="true"}'
                        values: '1+0x24'
                      - series: 'kube_replicaset_owner{namespace="sentrix",replicaset="front-v",owner_kind="Deployment",owner_name="frontend",owner_is_controller="true"}'
                        values: '1+0x24'
                      - series: 'container_cpu_usage_seconds_total{namespace="sentrix",pod="p",container="server"}'
                        values: '0+2.5x24'
                      - series: 'kube_pod_container_resource_limits{namespace="sentrix",pod="p",container="server",resource="cpu"}'
                        values: '0.5+0x24'
                      - series: 'container_cpu_cfs_throttled_periods_total{namespace="sentrix",pod="p",container="server"}'
                        values: '0+2x24'
                      - series: 'container_cpu_cfs_periods_total{namespace="sentrix",pod="p",container="server"}'
                        values: '0+10x24'
                    promql_expr_test:
                """);
        String frontend = "{deployment=\"frontend\"}";
        yaml.append(check(MetricDefinition.available_replicas, "frontend", frontend, "0"));
        yaml.append(check(MetricDefinition.desired_replicas, "frontend", frontend, "1"));
        yaml.append(check(MetricDefinition.desired_replicas, "conflict", null, null));
        yaml.append(check(MetricDefinition.cpu_limit_ratio, "frontend", frontend, "1"));
        yaml.append(check(MetricDefinition.cpu_throttling_ratio, "frontend", frontend, "0.2"));
        yaml.append(check(MetricDefinition.cpu_limit_ratio, "other", null, null));
        yaml.append(check(MetricDefinition.cpu_throttling_ratio, "other", null, null));
        Path target = Path.of("target", "prometheus-query-tests.yaml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, yaml);
        assertEquals(11, MetricDefinition.values().length);
        // The CI promtool step executes this generated fixture against the real query engine.
        assertTrue(Files.size(target) > 0);
    }

    private static String check(MetricDefinition metric, String service, String labels, String expected) {
        String expression = metric.expression("sentrix", service, 30);
        // promtool compares NaN unequal to itself; assert its defining property instead.
        if (".nan".equals(expected)) {
            expression = "count((" + expression + ") != (" + expression + "))";
            labels = "{}";
            expected = "1";
        }
        String header = "      - expr: '" + expression.replace("'", "''") + "'\n        eval_time: 2m\n";
        return header + (labels == null ? "        exp_samples: []\n" :
                "        exp_samples:\n          - labels: '" + labels + "'\n            value: " + expected + "\n");
    }
}
