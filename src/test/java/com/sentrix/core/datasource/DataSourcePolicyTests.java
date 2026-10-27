package com.sentrix.core.datasource;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class DataSourcePolicyTests {
    @Test
    void canonicalizesExactUrlsButRejectsAmbiguousOrCredentialBearingInputs() {
        var policy = new DataSourceTargetPolicy("HTTP://LOCALHOST:80/prom/,https://example.com:443,http://[::1]:9090");
        assertEquals("http://localhost/prom", policy.validate(" http://localhost/prom/ "));
        assertEquals("https://example.com", policy.validate("https://example.com/"));
        assertEquals("http://[::1]:9090", policy.validate("http://[::1]:9090"));
        for (String input : new String[]{"file:///tmp/x", "http://user:secret@localhost/prom", "http://localhost/prom?q=x",
                "http://localhost/prom#x", "http://localhost/prom/../", "http://localhost/%70rom",
                "http://localhost//prom", "http://localhost:0/prom", "http://localhost:65536/prom",
                "http://localhost/prom.evil", "http://localhost.evil/prom", "http://localhost:81/prom"}) {
            assertThrows(ResponseStatusException.class, () -> policy.validate(input), input);
        }
    }

    @Test
    void defaultsToDenyAndRechecksSavedTargetsBeforeNetworkAccess() {
        var policy = new DataSourceTargetPolicy("");
        assertThrows(ResponseStatusException.class, () -> policy.validate("http://127.0.0.1:9090"));
        var source = new ObservationDataSource(1L, DataSourceType.PROMETHEUS);
        source.configure("Prometheus", "http://127.0.0.1:9090", 1);
        var result = new DataSourceProbe(policy).check(source);
        assertEquals("TARGET_NOT_ALLOWED", result.failureCode());
        assertNull(result.httpStatus());
    }

    @Test
    void distinguishesDnsTlsAndConnectionErrorsWithoutReturningExceptionSecrets() {
        assertEquals("DNS_ERROR", DataSourceProbe.failureCode(new UnknownHostException("private-host")));
        assertEquals("DNS_ERROR", DataSourceProbe.failureCode(new ConnectException("failed")
                .initCause(new UnresolvedAddressException())));
        assertEquals("TLS_ERROR", DataSourceProbe.failureCode(new SSLHandshakeException("certificate")));
        assertEquals("CONNECTION_FAILED", DataSourceProbe.failureCode(new ConnectException("refused")));
    }
}
