package com.sentrix.core.datasource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.*;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import javax.net.ssl.SSLException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class DataSourceProbe {
    private final DataSourceTargetPolicy policy;

    DataSourceProbe(DataSourceTargetPolicy policy) { this.policy = policy; }

    Result check(ObservationDataSource source) {
        long started = System.nanoTime();
        // Recheck policy at use time so removing a target also blocks previously saved settings.
        if (!policy.allows(source.baseUrl)) return new Result(0, null, "TARGET_NOT_ALLOWED");
        var timeout = Duration.ofSeconds(source.timeoutSeconds);
        var request = HttpRequest.newBuilder(URI.create(source.baseUrl + source.type.readinessPath))
                .timeout(timeout).GET().build();
        try (var client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // Readiness uses only the HTTP status; never buffer an untrusted or endless response body.
            try (var body = response.body()) {
                int status = response.statusCode();
                String failure = status == 200 ? null
                        : status >= 300 && status < 400 ? "REDIRECT_REJECTED" : "HTTP_ERROR";
                return new Result(elapsed(started), status, failure);
            }
        } catch (HttpTimeoutException exception) {
            return new Result(elapsed(started), null, "TIMEOUT");
        } catch (IOException exception) {
            return new Result(elapsed(started), null, failureCode(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Connection test interrupted");
        }
    }

    static String failureCode(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) return "DNS_ERROR";
            if (cause instanceof SSLException) return "TLS_ERROR";
        }
        return exception instanceof ConnectException ? "CONNECTION_FAILED" : "IO_ERROR";
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    record Result(long latencyMs, Integer httpStatus, String failureCode) {}
}
