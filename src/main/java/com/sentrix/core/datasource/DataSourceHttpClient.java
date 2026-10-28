package com.sentrix.core.datasource;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.sentrix.core.datasource.PrometheusClient.error;

/** Shared bounded transport for operator-approved observation stores. */
@Component
class DataSourceHttpClient {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final DataSourceTargetPolicy policy;
    private final ObjectMapper mapper;
    DataSourceHttpClient(DataSourceTargetPolicy policy, ObjectMapper mapper) { this.policy=policy; this.mapper=mapper; }

    JsonNode read(String baseUrl, int timeoutSeconds, String path, Map<String,String> parameters, boolean post, String provider) {
        if (!policy.allows(baseUrl)) {
            throw error(HttpStatus.CONFLICT, "TARGET_NOT_ALLOWED", provider + " target is no longer allowed");
        }
        var params = new LinkedHashMap<>(parameters);

        String body = params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        var timeout = Duration.ofSeconds(timeoutSeconds);
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path + (post || body.isEmpty() ? "" : "?" + body))).timeout(timeout).header("Accept", "application/json");
        var request = (post ? builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)) : builder.GET()).build();
        var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request, info -> new LimitedBody());
            // Unlike a headers-only timeout, this also bounds a stalled response body.
            var response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                var failure = error(HttpStatus.BAD_GATEWAY, provider + "_HTTP_ERROR", "Observation server returned a non-200 response");
                failure.getBody().setProperty("upstreamStatus", response.statusCode());
                throw failure;
            }
            JsonNode root;
            try { root = mapper.readTree(response.body()); }
            catch (RuntimeException exception) { throw error(HttpStatus.BAD_GATEWAY, "INVALID_" + provider + "_RESPONSE", "Unexpected observation response"); }
            if (root == null || !root.isObject()) throw error(HttpStatus.BAD_GATEWAY, "INVALID_" + provider + "_RESPONSE", "Unexpected observation response");
            return root;
        } catch (TimeoutException exception) {
            throw error(HttpStatus.GATEWAY_TIMEOUT, provider + "_TIMEOUT", "Observation server query timed out");
        } catch (ExecutionException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof BodyTooLarge) throw error(HttpStatus.BAD_GATEWAY, "RESPONSE_TOO_LARGE", "Observation server response exceeds 4 MiB");
                if (cause instanceof HttpTimeoutException) throw error(HttpStatus.GATEWAY_TIMEOUT, provider + "_TIMEOUT", "Observation server query timed out");
            }
            throw error(HttpStatus.BAD_GATEWAY, provider + "_UNAVAILABLE", "Unable to read Observation server response");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "QUERY_INTERRUPTED", "Query interrupted");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            client.shutdownNow();
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

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
