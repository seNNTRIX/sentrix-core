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

/** Bounded human-facing trace browsing, not a complete ML export. */
@Tag(name="05. 요청 추적")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/traces")
class TraceController {
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceHttpClient http;
    private final ObjectMapper mapper;
    TraceController(EnvironmentRepository environments, ObservationDataSourceRepository sources,
                    DataSourceHttpClient http, ObjectMapper mapper) {
        this.environments=environments; this.sources=sources; this.http=http; this.mapper=mapper;
    }

    @Operation(summary="요청 추적 검색", description="service, from, to는 필수입니다. UTC ISO 8601 초 단위 시각, 최대 24시간, limit 1~100(기본 20)입니다. Tempo가 먼저 찾은 N건 안에서만 정렬하며 전체 최신 N건을 보장하지 않습니다.")
    @GetMapping
    SearchResult search(@PathVariable Long environmentId, @RequestParam String service,
                        @RequestParam Instant from, @RequestParam Instant to,
                        @RequestParam(defaultValue="20") int limit) {
        if (!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?") || limit<1 || limit>100
                || from.getNano()!=0 || to.getNano()!=0 || from.isBefore(Instant.EPOCH) || !from.isBefore(to)
                || Duration.between(from,to).compareTo(Duration.ofHours(24))>0) {
            throw error(HttpStatus.BAD_REQUEST,"INVALID_QUERY","Use a service, whole-second times up to 24 hours and limit 1..100");
        }
        String namespace=namespace(environmentId);
        var source=source(environmentId);
        String query="{ resource.service.namespace = "+mapper.writeValueAsString(namespace)
                +" && resource.service.name = "+mapper.writeValueAsString(service)+" }";
        var root=http.read(source.baseUrl,source.timeoutSeconds,"/api/search",Map.of("q",query,
                "start",Long.toString(from.getEpochSecond()),"end",Long.toString(to.getEpochSecond()),"limit",Integer.toString(limit)),false,"TEMPO");
        var traces=root.path("traces");
        // Protobuf JSON omits empty repeated fields (Tempo returns {"metrics":...}).
        if ((!traces.isMissingNode() && !traces.isArray()) || (traces.isMissingNode() && !root.path("metrics").isObject()) || traces.size()>limit) throw invalid();
        var items=new ArrayList<TraceSummary>();
        var seen=new HashSet<String>();
        for(var trace:traces) {
            String id=searchId(trace.path("traceID").asText());
            if (!validId(id,32) || !seen.add(id) || (trace.has("durationMs") && (!trace.path("durationMs").isIntegralNumber() || !trace.path("durationMs").canConvertToLong()))) throw invalid();
            long duration=trace.path("durationMs").asLong(0); // protobuf omits a zero duration
            if (duration<0) throw invalid();
            long start=nanos(trace.path("startTimeUnixNano"));
            items.add(new TraceSummary(id,instant(start),duration,trace.path("rootServiceName").asText(""),trace.path("rootTraceName").asText("")));
        }
        items.sort(Comparator.comparing(TraceSummary::startedAt).reversed().thenComparing(TraceSummary::traceId));
        boolean limitReached=items.size()==limit;
        var warnings=limitReached?List.of("LIMIT_REACHED"):List.<String>of();
        return new SearchResult(environmentId,namespace,service,from,to,Instant.now(),limit,limitReached,
                items.isEmpty()?"NO_DATA":limitReached?"PARTIAL":"AVAILABLE",warnings,items);
    }

    @Operation(summary="요청 추적 상세 조회", description="현재 환경의 처리 구간, 부모 관계와 소요 시간을 반환합니다. ID는 0이 아닌 32자리 소문자 hex입니다. 미수집·만료·다른 환경의 요청은 404입니다. 부모 누락 등 불완전 수집은 warnings를 확인하세요. UNSET은 정상 판정이 아니며 상세는 최대 5000 span입니다.")
    @GetMapping("/{traceId}")
    TraceDetail detail(@PathVariable Long environmentId,@PathVariable String traceId) {
        if (!validId(traceId,32)) throw error(HttpStatus.BAD_REQUEST,"INVALID_QUERY","Use a non-zero 32-character lowercase hex trace ID");
        String namespace=namespace(environmentId);
        var source=source(environmentId);
        JsonNode root;
        try { root=http.read(source.baseUrl,source.timeoutSeconds,"/api/v2/traces/"+traceId,Map.of(),false,"TEMPO"); }
        catch(ResponseStatusException exception) {
            if (exception.getBody().getProperties()!=null && Integer.valueOf(404).equals(exception.getBody().getProperties().get("upstreamStatus"))) throw notFound();
            throw exception;
        }
        var trace=root.path("trace");
        var batches=trace.has("resourceSpans")?trace.path("resourceSpans"):trace.path("batches");
        if (!batches.isArray()) throw invalid();
        var spans=new ArrayList<Span>();
        var warnings=new LinkedHashSet<String>();
        String upstreamStatus=root.path("status").asText("COMPLETE");
        if (!Set.of("COMPLETE","0","PARTIAL","1").contains(upstreamStatus)) throw invalid();
        if (upstreamStatus.equals("PARTIAL") || upstreamStatus.equals("1")) warnings.add("UPSTREAM_PARTIAL");
        var seen=new HashSet<String>();
        for(var batch:batches) {
            String spanNamespace=attribute(batch.path("resource").path("attributes"),"service.namespace");
            if (!namespace.equals(spanNamespace)) { warnings.add("OUTSIDE_ENVIRONMENT_OMITTED"); continue; }
            String service=attribute(batch.path("resource").path("attributes"),"service.name");
            if(service.isEmpty()) { service="unknown";warnings.add("MISSING_SERVICE_NAME"); }
            var scopes=batch.has("scopeSpans")?batch.path("scopeSpans"):batch.path("instrumentationLibrarySpans");
            if (!scopes.isArray()) throw invalid();
            for(var scope:scopes) {
                if(!scope.path("spans").isArray()) throw invalid();
                for(var span:scope.path("spans")) {
                    if (spans.size()>=5000) throw error(HttpStatus.BAD_GATEWAY,"TRACE_TOO_LARGE","Trace exceeds 5000 spans");
                    String id=wireId(span.path("spanId").asText(),8), actualTrace=wireId(span.path("traceId").asText(),16);
                    String parent=span.path("parentSpanId").asText("");
                    if (!parent.isEmpty()) parent=wireId(parent,8);
                    if(parent.equals("0000000000000000"))parent="";
                    if(!traceId.equals(actualTrace) || !seen.add(id) || id.equals(parent) || id.equals("0000000000000000")) throw invalid();
                    long start=nanos(span.path("startTimeUnixNano")),end=nanos(span.path("endTimeUnixNano"));
                    if (end<start) throw invalid();
                    String status=status(span.path("status").path("code").asText("0"));
                    spans.add(new Span(id,parent,service,span.path("name").asText(""),kind(span.path("kind").asText("0")),
                            status,instant(start),instant(end),(end-start)/1_000_000.0,start));
                }
            }
        }
        if(spans.isEmpty())throw notFound();
        if(spans.stream().anyMatch(s->!s.parentSpanId.isEmpty()&&!seen.contains(s.parentSpanId)))warnings.add("MISSING_PARENT_SPANS");
        // Parent links may be incomplete; reject cycles instead of rendering invented trees.
        var parents=new HashMap<String,String>();spans.forEach(s->parents.put(s.spanId,s.parentSpanId));
        var completed=new HashSet<String>();
        for(var span:spans) {
            var path=new HashSet<String>();String cursor=span.spanId;
            while(parents.containsKey(cursor)&&!completed.contains(cursor)) {
                if(!path.add(cursor))throw invalid();cursor=parents.get(cursor);
            }
            completed.addAll(path);
        }
        spans.sort(Comparator.comparingLong(Span::startNanos).thenComparing(Span::spanId));
        Instant startedAt=spans.getFirst().startedAt;
        Instant endedAt=spans.stream().map(Span::endedAt).max(Comparator.naturalOrder()).orElseThrow();
        return new TraceDetail(environmentId,namespace,traceId,Instant.now(),startedAt,endedAt,
                Duration.between(startedAt,endedAt).toNanos()/1_000_000.0,
                warnings.isEmpty()?"AVAILABLE":"PARTIAL",List.copyOf(warnings),spans);
    }

    private String namespace(Long id) {return environments.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"ENVIRONMENT_NOT_FOUND","Environment not found")).getNamespace();}
    private ObservationDataSource source(Long id) {return sources.findByEnvironmentIdAndType(id,DataSourceType.TEMPO).orElseThrow(()->error(HttpStatus.CONFLICT,"TEMPO_NOT_CONFIGURED","Configure Tempo first"));}
    private static String attribute(JsonNode attributes,String key) {
        if(!attributes.isArray())throw invalid();
        String found="";boolean present=false;
        for(var attr:attributes)if(key.equals(attr.path("key").asText())) {
            if(present||!attr.path("value").path("stringValue").isTextual())throw invalid();
            found=attr.path("value").path("stringValue").asText();present=true;
        }
        return found;
    }
    // Tempo versions use protobuf JSON base64 IDs or OTLP JSON hex IDs.
    private static String wireId(String value,int bytes) {
        if(value.matches("[0-9a-fA-F]{"+(bytes*2)+"}"))return value.toLowerCase(Locale.ROOT);
        try {byte[] decoded=Base64.getDecoder().decode(value);if(decoded.length==bytes)return HexFormat.of().formatHex(decoded);}
        catch(IllegalArgumentException ignored) {}
        throw invalid();
    }
    // Search metadata may omit leading zeroes; detail IDs remain canonical 128-bit hex.
    private static String searchId(String value) {
        if(!value.matches("[0-9a-fA-F]{1,32}"))throw invalid();
        return "0".repeat(32-value.length())+value.toLowerCase(Locale.ROOT);
    }
    private static boolean validId(String value,int size) {return value.matches("[0-9a-f]{"+size+"}")&&!value.chars().allMatch(c->c=='0');}
    private static long nanos(JsonNode node) {
        if(!node.isTextual()&&!node.isIntegralNumber())throw invalid();
        try {long value=Long.parseLong(node.asText());if(value>=0)return value;}catch(NumberFormatException ignored){}
        throw invalid();
    }
    private static Instant instant(long nanos) {return Instant.ofEpochSecond(nanos/1_000_000_000L,nanos%1_000_000_000L);}
    private static String status(String code) {return switch(code){case "0","STATUS_CODE_UNSET"->"UNSET";case "1","STATUS_CODE_OK"->"OK";case "2","STATUS_CODE_ERROR"->"ERROR";default->throw invalid();};}
    private static String kind(String code) {return switch(code){case "1","SPAN_KIND_INTERNAL"->"INTERNAL";case "2","SPAN_KIND_SERVER"->"SERVER";case "3","SPAN_KIND_CLIENT"->"CLIENT";case "4","SPAN_KIND_PRODUCER"->"PRODUCER";case "5","SPAN_KIND_CONSUMER"->"CONSUMER";case "0","SPAN_KIND_UNSPECIFIED"->"UNSPECIFIED";default->throw invalid();};}
    private static ResponseStatusException invalid(){return error(HttpStatus.BAD_GATEWAY,"INVALID_TEMPO_RESPONSE","Unexpected Tempo response");}
    private static ResponseStatusException notFound(){return error(HttpStatus.NOT_FOUND,"TRACE_NOT_FOUND","No trace spans found in this environment");}
    record TraceSummary(String traceId,Instant startedAt,long durationMs,String rootService,String rootOperation){}
    record SearchResult(Long environmentId,String namespace,String service,Instant from,Instant to,Instant queriedAt,int limit,
                        boolean limitReached,String dataStatus,List<String>warnings,List<TraceSummary>items){}
    record Span(String spanId,String parentSpanId,String service,String operation,String kind,String status,
                Instant startedAt,Instant endedAt,double durationMs,@com.fasterxml.jackson.annotation.JsonIgnore long startNanos){}
    record TraceDetail(Long environmentId,String namespace,String traceId,Instant queriedAt,Instant startedAt,Instant endedAt,
                       double durationMs,String dataStatus,List<String>warnings,List<Span>spans){}
}
