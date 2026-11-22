package com.sentrix.core.analysis;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.sentrix.core.analysis.AnalysisProtocol.*;

@Component
public class AnalysisModelClient {
    private static final int MAX_BYTES = 1024 * 1024;
    private final AnalysisModelSettings settings;
    private final ObjectMapper mapper;

    public AnalysisModelClient(AnalysisModelSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public Response analyze(Request input) {
        if (!settings.enabled()) throw new AnalysisFailure("MODEL_UNAVAILABLE");
        validateRequest(input);
        var body = mapper.createObjectNode();
        body.put("contractVersion", input.contractVersion());
        body.put("jobId", input.jobId().toString());
        body.put("environmentId", input.environmentId());
        body.put("namespace", input.namespace());
        body.put("windowStart", input.windowStart().toString());
        body.put("windowEnd", input.windowEnd().toString());
        body.put("featureSchemaVersion", input.featureSchemaVersion());
        body.put("modelVersion", input.modelVersion());
        var timeout = Duration.ofSeconds(settings.timeoutSeconds());
        var request = HttpRequest.newBuilder(settings.endpoint()).timeout(timeout)
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
        var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request, info -> new LimitedBody());
            var response = pending.get(settings.timeoutSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() >= 300 && response.statusCode() < 400) throw new AnalysisFailure("MODEL_REDIRECT_REJECTED");
            if (response.statusCode() != 200) throw new AnalysisFailure("MODEL_HTTP_ERROR");
            return parse(response.body(), input);
        } catch (TimeoutException ex) {
            throw new AnalysisFailure("MODEL_TIMEOUT");
        } catch (ExecutionException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof BodyTooLarge) throw new AnalysisFailure("MODEL_RESPONSE_TOO_LARGE");
                if (cause instanceof HttpTimeoutException) throw new AnalysisFailure("MODEL_TIMEOUT");
            }
            throw new AnalysisFailure("MODEL_UNAVAILABLE");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AnalysisFailure("ANALYSIS_INTERRUPTED");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            client.shutdownNow();
        }
    }

    private void validateRequest(Request value) {
        if (value == null || !CONTRACT_VERSION.equals(value.contractVersion()) || value.jobId() == null
                || value.environmentId() <= 0 || value.namespace() == null
                || !value.namespace().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
                || value.windowStart() == null || value.windowEnd() == null
                || value.windowStart().getNano() != 0 || value.windowEnd().getNano() != 0
                || value.windowEnd().getEpochSecond() < 30 || value.windowEnd().getEpochSecond() % 10 != 0
                || value.windowEnd().isAfter(Instant.now())
                || !Duration.between(value.windowStart(), value.windowEnd()).equals(Duration.ofSeconds(30))
                || !settings.featureSchemaVersion().equals(value.featureSchemaVersion())
                || !settings.modelVersion().equals(value.modelVersion())) throw new AnalysisFailure("INVALID_ANALYSIS_REQUEST");
    }

    private Response parse(byte[] body, Request input) {
        try {
            JsonNode root = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
            if (root == null || !root.isObject()) throw invalid();
            String contract = text(root, "contractVersion", 100);
            UUID jobId = UUID.fromString(text(root, "jobId", 36));
            JsonNode environment = root.path("environmentId");
            if (!environment.isIntegralNumber() || !environment.canConvertToLong()) throw invalid();
            long environmentId = environment.longValue();
            String namespace = text(root, "namespace", 63);
            Instant start = Instant.parse(text(root, "windowStart", 40));
            Instant end = Instant.parse(text(root, "windowEnd", 40));
            String feature = text(root, "featureSchemaVersion", 100);
            String model = text(root, "modelVersion", 100);
            if (!input.contractVersion().equals(contract) || !input.jobId().equals(jobId)
                    || input.environmentId() != environmentId || !input.namespace().equals(namespace)
                    || !input.windowStart().equals(start) || !input.windowEnd().equals(end)
                    || !input.featureSchemaVersion().equals(feature) || !input.modelVersion().equals(model)) throw invalid();
            Decision decision = Decision.valueOf(text(root, "decision", 20));
            Double score = number(root, "score", true);
            String abstainReason = nullableText(root, "abstainReason", 500);
            JsonNode items = root.path("candidates");
            if (!items.isArray() || items.size() > 20) throw invalid();
            var candidates = new ArrayList<Candidate>();
            var targets = new HashSet<List<String>>();
            for (JsonNode item : items) {
                JsonNode rank = item.path("rank");
                if (!item.isObject() || !rank.isIntegralNumber() || !rank.canConvertToInt() || rank.intValue() != candidates.size() + 1) throw invalid();
                CandidateKind kind = CandidateKind.valueOf(text(item, "kind", 10));
                String service = nullableText(item, "service", 253);
                String source = nullableText(item, "source", 253);
                String target = nullableText(item, "target", 253);
                if (kind == CandidateKind.NODE && (service == null || source != null || target != null)) throw invalid();
                if (kind == CandidateKind.EDGE && (service != null || source == null || target == null || source.equals(target))) throw invalid();
                if (!targets.add(Arrays.asList(kind.name(), service, source, target))) throw invalid();
                String faultType = text(item, "faultType", 100);
                double candidateScore = number(item, "score", false);
                JsonNode lines = item.path("evidence");
                if (!lines.isArray() || lines.size() > 20) throw invalid();
                var evidence = new ArrayList<String>();
                for (JsonNode line : lines) evidence.add(textValue(line, 1000));
                candidates.add(new Candidate(rank.intValue(), kind, service, source, target, faultType, candidateScore, List.copyOf(evidence)));
            }
            if (decision == Decision.ANOMALY && candidates.isEmpty()) throw invalid();
            if (decision != Decision.ANOMALY && !candidates.isEmpty()) throw invalid();
            if (decision == Decision.ABSTAIN && abstainReason == null) throw invalid();
            if (decision != Decision.ABSTAIN && abstainReason != null) throw invalid();
            return new Response(contract, jobId, environmentId, namespace, start, end, feature, model,
                    decision, score, List.copyOf(candidates), abstainReason);
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private static String text(JsonNode node, String name, int max) { return textValue(node.path(name), max); }
    private static String textValue(JsonNode value, int max) {
        if (!value.isString() || value.stringValue().isBlank() || value.stringValue().length() > max
                || value.stringValue().chars().anyMatch(c -> Character.isISOControl(c))) throw invalid();
        return value.stringValue();
    }
    private static String nullableText(JsonNode node, String name, int max) {
        JsonNode value = node.path(name);
        if (value.isMissingNode()) throw invalid();
        return value.isNull() ? null : textValue(value, max);
    }
    private static Double number(JsonNode node, String name, boolean nullable) {
        JsonNode value = node.path(name);
        if (nullable && value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalid();
        return value.doubleValue();
    }
    private static AnalysisFailure invalid() { return new AnalysisFailure("INVALID_MODEL_RESPONSE"); }

    public static class AnalysisFailure extends RuntimeException {
        private final String code;
        public AnalysisFailure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    private static class BodyTooLarge extends RuntimeException {}
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
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
