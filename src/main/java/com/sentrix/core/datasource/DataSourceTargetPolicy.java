package com.sentrix.core.datasource;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class DataSourceTargetPolicy {
    private final Set<String> allowedUrls;

    DataSourceTargetPolicy(@Value("${sentrix.data-sources.allowed-base-urls:}") String urls) {
        allowedUrls = Arrays.stream(urls.split(",")).map(String::strip)
                .filter(s -> !s.isEmpty()).map(DataSourceTargetPolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    String validate(String url) {
        String normalized;
        try {
            normalized = normalize(url);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "baseUrl must be an HTTP(S) base URL without credentials, query or fragment");
        }
        if (!allows(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "baseUrl is not in the server-managed data-source allowlist");
        }
        return normalized;
    }

    boolean allows(String normalizedUrl) { return allowedUrls.contains(normalizedUrl); }

    static String normalize(String value) {
        URI uri = URI.create(value.strip());
        String scheme = uri.getScheme();
        int port = uri.getPort();
        String path = uri.getRawPath();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || port == 0 || port > 65535 || path.contains("%") || path.contains("//")
                || Arrays.stream(path.split("/")).anyMatch(p -> p.equals(".") || p.equals(".."))) {
            throw new IllegalArgumentException("Invalid base URL");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port) + path;
    }
}
