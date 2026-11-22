package com.sentrix.core.analysis;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only server operators can select the model endpoint and version pair. */
@Component
public class AnalysisModelSettings {
    private final boolean enabled;
    private final URI endpoint;
    private final String modelVersion;
    private final String featureSchemaVersion;
    private final int timeoutSeconds;

    public AnalysisModelSettings(@Value("${sentrix.analysis.enabled:false}") boolean enabled,
            @Value("${sentrix.analysis.base-url:}") String baseUrl,
            @Value("${sentrix.analysis.model-version:}") String modelVersion,
            @Value("${sentrix.analysis.feature-schema-version:}") String featureSchemaVersion,
            @Value("${sentrix.analysis.timeout-seconds:10}") int timeoutSeconds) {
        this.enabled = enabled;
        this.modelVersion = version(modelVersion);
        this.featureSchemaVersion = version(featureSchemaVersion);
        this.timeoutSeconds = timeoutSeconds;
        if (timeoutSeconds < 1 || timeoutSeconds > 60) throw invalid();
        this.endpoint = endpoint(baseUrl);
        if (enabled && (endpoint == null || this.modelVersion.isEmpty() || this.featureSchemaVersion.isEmpty())) throw invalid();
    }

    public boolean enabled() { return enabled; }
    public URI endpoint() { return endpoint; }
    public String modelVersion() { return modelVersion; }
    public String featureSchemaVersion() { return featureSchemaVersion; }
    public int timeoutSeconds() { return timeoutSeconds; }

    private static String version(String value) {
        if (value == null || (!value.isEmpty() && !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) throw invalid();
        return value;
    }

    private static URI endpoint(String baseUrl) {
        if (baseUrl == null) throw invalid();
        if (baseUrl.isEmpty()) return null;
        try {
            URI uri = URI.create(baseUrl);
            String path = uri.getRawPath();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                    || path == null || !path.matches("[/A-Za-z0-9._~-]*") || path.contains("//")
                    || java.util.Arrays.stream(path.split("/")).anyMatch(segment -> segment.equals(".") || segment.equals(".."))
                    || !uri.normalize().getRawPath().equals(path)) throw invalid();
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return URI.create(base + "/v1/analyses");
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid analysis server configuration");
    }
}
