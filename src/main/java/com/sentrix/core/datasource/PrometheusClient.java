package com.sentrix.core.datasource;

import com.sentrix.core.environment.EnvironmentRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PrometheusClient {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceTargetPolicy policy;
    private final ObjectMapper mapper;

    PrometheusClient(EnvironmentRepository environments, ObservationDataSourceRepository sources,
                     DataSourceTargetPolicy policy, ObjectMapper mapper) {
        this.environments = environments;
        this.sources = sources;
        this.policy = policy;
        this.mapper = mapper;
    }

    public Connection connection(Long environmentId) {
        var environment = environments.findById(environmentId).orElseThrow(() ->
                error(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Environment not found"));
        var source = sources.findByEnvironmentIdAndType(environmentId, DataSourceType.PROMETHEUS)
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "PROMETHEUS_NOT_CONFIGURED", "Configure Prometheus first"));
        return new Connection(environment.getNamespace(), source.baseUrl, source.timeoutSeconds);
    }

    public QueryResult query(Connection connection, Map<String, String> parameters, boolean range) {
        if (!policy.allows(connection.baseUrl())) {
            throw error(HttpStatus.CONFLICT, "TARGET_NOT_ALLOWED", "Prometheus target is no longer allowed");
        }
        var params = new LinkedHashMap<>(parameters);
        params.put("timeout", connection.timeoutSeconds() + "s");
        String body = params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        var timeout = Duration.ofSeconds(connection.timeoutSeconds());
        var request = HttpRequest.newBuilder(URI.create(connection.baseUrl() + "/api/v1/" + (range ? "query_range" : "query")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request, info -> new LimitedBody());
            // Unlike a headers-only timeout, this also bounds a stalled response body.
            var response = pending.get(connection.timeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                var failure = error(HttpStatus.BAD_GATEWAY, "PROMETHEUS_HTTP_ERROR", "Prometheus returned a non-200 response");
                failure.getBody().setProperty("upstreamStatus", response.statusCode());
                throw failure;
            }
            JsonNode root;
            try { root = mapper.readTree(response.body()); }
            catch (RuntimeException exception) { throw invalidResponse(); }
            if (root == null || !root.isObject()) throw invalidResponse();
            if (!"success".equals(root.path("status").asText())) {
                throw error(HttpStatus.BAD_GATEWAY, "PROMETHEUS_QUERY_FAILED", "Prometheus could not execute the query");
            }
            var data = root.path("data");
            if (!(range ? "matrix" : "vector").equals(data.path("resultType").asText())
                    || !data.path("result").isArray()) throw invalidResponse();
            return new QueryResult(data.path("result"), root.path("warnings").size() > 0 || root.path("infos").size() > 0);
        } catch (TimeoutException exception) {
            throw error(HttpStatus.GATEWAY_TIMEOUT, "PROMETHEUS_TIMEOUT", "Prometheus query timed out");
        } catch (ExecutionException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof BodyTooLarge) throw error(HttpStatus.BAD_GATEWAY, "RESPONSE_TOO_LARGE", "Prometheus response exceeds 4 MiB");
                if (cause instanceof HttpTimeoutException) throw error(HttpStatus.GATEWAY_TIMEOUT, "PROMETHEUS_TIMEOUT", "Prometheus query timed out");
            }
            throw error(HttpStatus.BAD_GATEWAY, "PROMETHEUS_UNAVAILABLE", "Unable to read Prometheus response");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "QUERY_INTERRUPTED", "Query interrupted");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            client.shutdownNow();
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

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

    private static class BodyTooLarge extends RuntimeException {}

    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - received) {
                    failed = true;
                    subscription.cancel();
                    delegate.onError(new BodyTooLarge());
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
