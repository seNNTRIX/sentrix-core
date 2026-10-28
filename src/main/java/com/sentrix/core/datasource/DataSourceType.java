package com.sentrix.core.datasource;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

enum DataSourceType {
    PROMETHEUS("/-/ready"), TEMPO("/ready"), LOKI("/ready");

    final String readinessPath;

    DataSourceType(String readinessPath) { this.readinessPath = readinessPath; }

    static DataSourceType parse(String value) {
        return switch (value) {
            case "prometheus" -> PROMETHEUS;
            case "tempo" -> TEMPO;
            case "loki" -> LOKI;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "type must be prometheus, tempo or loki");
        };
    }
}
