package com.sentrix.core.datasource;

import com.sentrix.core.environment.EnvironmentRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Component
public class PrometheusClient {
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceHttpClient http;

    PrometheusClient(EnvironmentRepository environments, ObservationDataSourceRepository sources,
                     DataSourceHttpClient http) {
        this.environments = environments;
        this.sources = sources;
        this.http = http;
    }

    public Connection connection(Long environmentId) {
        var environment = environments.findById(environmentId).orElseThrow(() ->
                error(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Environment not found"));
        var source = sources.findByEnvironmentIdAndType(environmentId, DataSourceType.PROMETHEUS)
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "PROMETHEUS_NOT_CONFIGURED", "Configure Prometheus first"));
        return new Connection(environment.getNamespace(), source.baseUrl, source.timeoutSeconds);
    }

    public QueryResult query(Connection connection, Map<String, String> parameters, boolean range) {
        var params = new LinkedHashMap<>(parameters);
        params.put("timeout", connection.timeoutSeconds() + "s");
        JsonNode root = http.read(connection.baseUrl(), connection.timeoutSeconds(), "/api/v1/" + (range ? "query_range" : "query"), params, true, "PROMETHEUS");
        if (!"success".equals(root.path("status").asText())) {
            throw error(HttpStatus.BAD_GATEWAY, "PROMETHEUS_QUERY_FAILED", "Prometheus could not execute the query");
        }
        var data = root.path("data");
        if (!(range ? "matrix" : "vector").equals(data.path("resultType").asText()) || !data.path("result").isArray()) throw invalidResponse();
        return new QueryResult(data.path("result"), root.path("warnings").size() > 0 || root.path("infos").size() > 0);
    }

    public static ResponseStatusException invalidResponse() {
        return error(HttpStatus.BAD_GATEWAY, "INVALID_PROMETHEUS_RESPONSE", "Unexpected Prometheus response format");
    }

    public static ResponseStatusException error(HttpStatus status, String code, String message) {
        var error = new ResponseStatusException(status, message);
        error.getBody().setProperty("code", code);
        return error;
    }

    public record Connection(String namespace, String baseUrl, int timeoutSeconds) {}
    public record QueryResult(JsonNode series, boolean hasWarnings) {}

}
