package com.sentrix.core.datasource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.sentrix.core.environment.EnvironmentRepository;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.sentrix.core.datasource.PrometheusClient.error;

@Tag(name="04. 로그")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/logs")
class LogController {
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceHttpClient http;
    private final ObjectMapper mapper;
    LogController(EnvironmentRepository environments, ObservationDataSourceRepository sources,
                  DataSourceHttpClient http, ObjectMapper mapper) {
        this.environments=environments; this.sources=sources; this.http=http; this.mapper=mapper;
    }

    @Operation(summary="로그 검색", description="service, from, to는 필수입니다. UTC ISO 8601 시각, from 포함·to 제외, 최대 24시간입니다. limit은 1~500(기본 100), search는 제어문자 없는 최대 200자 원문 포함 검색입니다. 최신순 결과가 상한을 넘으면 truncated=true입니다. 화면 검색용이며 ML 전체 구간 수집용이 아닙니다.")
    @GetMapping
    LogResult logs(@PathVariable Long environmentId, @RequestParam String service,
                   @RequestParam Instant from, @RequestParam Instant to,
                   @RequestParam(defaultValue="") String search, @RequestParam(defaultValue="100") int limit) {
        if (!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?") || limit<1 || limit>500
                || search.length()>200 || search.chars().anyMatch(Character::isISOControl)
                || !from.isBefore(to) || from.isBefore(Instant.EPOCH) || Duration.between(from,to).compareTo(Duration.ofHours(24))>0) {
            throw error(HttpStatus.BAD_REQUEST,"INVALID_QUERY","Use a service, up to 24 hours, limit 1..500 and search up to 200 characters without controls");
        }
        long start=nanos(from), end=nanos(to);
        var environment=environments.findById(environmentId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"ENVIRONMENT_NOT_FOUND","Environment not found"));
        var source=sources.findByEnvironmentIdAndType(environmentId,DataSourceType.LOKI)
                .orElseThrow(()->error(HttpStatus.CONFLICT,"LOKI_NOT_CONFIGURED","Configure Loki first"));
        String namespace=environment.getNamespace();
        String query="{k8s_namespace_name="+mapper.writeValueAsString(namespace)+",k8s_deployment_name="+mapper.writeValueAsString(service)+"}";
        if (!search.isEmpty()) query+=" |= "+mapper.writeValueAsString(search);
        JsonNode root=http.read(source.baseUrl,source.timeoutSeconds,"/loki/api/v1/query_range",
                Map.of("query",query,"start",Long.toString(start),"end",Long.toString(end),"limit",Integer.toString(limit+1),"direction","backward"),false,"LOKI");
        if (!"success".equals(root.path("status").asText())) throw error(HttpStatus.BAD_GATEWAY,"LOKI_QUERY_FAILED","Loki could not execute the query");
        var data=root.path("data");
        if (!"streams".equals(data.path("resultType").asText()) || !data.path("result").isArray()) throw invalid();
        var entries=new ArrayList<LogEntry>();
        for (var stream:data.path("result")) {
            var labels=stream.path("stream");
            if (!namespace.equals(labels.path("k8s_namespace_name").asText()) || !service.equals(labels.path("k8s_deployment_name").asText())
                    || !stream.path("values").isArray()) throw invalid();
            for (var value:stream.path("values")) {
                if (!value.isArray() || value.size()<2 || value.size()>3 || !value.get(0).isTextual() || !value.get(1).isTextual()
                        || (value.size()==3 && !value.get(2).isObject())) throw invalid();
                long timestamp;
                try { timestamp=Long.parseLong(value.get(0).asText()); } catch (NumberFormatException exception) { throw invalid(); }
                if (timestamp<start || timestamp>=end || entries.size()>=limit+1) throw invalid();
                var metadata=value.size()==3?value.get(2):mapper.createObjectNode();
                entries.add(new LogEntry(Instant.ofEpochSecond(timestamp/1_000_000_000L,timestamp%1_000_000_000L),
                        Long.toString(timestamp),label(labels,metadata,"k8s_pod_name"),label(labels,metadata,"k8s_container_name"),value.get(1).asText()));
            }
        }
        entries.sort(Comparator.comparing(LogEntry::timestamp).reversed().thenComparing(LogEntry::pod).thenComparing(LogEntry::container).thenComparing(LogEntry::message));
        boolean truncated=entries.size()>limit;
        var warnings=new ArrayList<String>();
        if (root.path("warnings").size()>0 || root.path("infos").size()>0) warnings.add("UPSTREAM_WARNING");
        if (truncated) warnings.add("LIMIT_REACHED");
        return new LogResult(environmentId,namespace,service,from,to,Instant.now(),search,limit,truncated,
                entries.isEmpty()?"NO_DATA":warnings.isEmpty()?"AVAILABLE":"PARTIAL",warnings,List.copyOf(entries.subList(0,Math.min(limit,entries.size()))));
    }
    private static String label(JsonNode labels, JsonNode metadata, String key) {
        return labels.has(key)?labels.path(key).asText():metadata.path(key).asText("");
    }
    private static long nanos(Instant value) {
        try { return Math.addExact(Math.multiplyExact(value.getEpochSecond(),1_000_000_000L),value.getNano()); }
        catch (ArithmeticException exception) { throw error(HttpStatus.BAD_REQUEST,"INVALID_QUERY","Timestamp is outside the supported range"); }
    }
    private static ResponseStatusException invalid() { return error(HttpStatus.BAD_GATEWAY,"INVALID_LOKI_RESPONSE","Unexpected Loki response"); }
    record LogEntry(Instant timestamp,String timestampNanos,String pod,String container,String message) {}
    record LogResult(Long environmentId,String namespace,String service,Instant from,Instant to,Instant queriedAt,
                     String search,int limit,boolean truncated,String dataStatus,List<String> warnings,List<LogEntry> items) {}
}
