package com.sentrix.core.datasource;

import com.sentrix.core.environment.EnvironmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.sentrix.core.datasource.PrometheusClient.error;

@Tag(name="02. 데이터 소스")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/collection-status")
class CollectionStatusController {
    static final int LOOKBACK_SECONDS=1800, FRESHNESS_SECONDS=180;
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceHttpClient http;
    private final PrometheusClient prometheus;
    private final ObjectMapper mapper;

    CollectionStatusController(EnvironmentRepository environments, ObservationDataSourceRepository sources,
                               DataSourceHttpClient http, PrometheusClient prometheus, ObjectMapper mapper) {
        this.environments=environments; this.sources=sources; this.http=http; this.prometheus=prometheus; this.mapper=mapper;
    }

    @Operation(summary="서비스별 수집 데이터 최신성 확인", description="service는 필수입니다. 최근 30분에서 Deployment 지표·요청 지표·로그·trace의 시각을 확인합니다. 180초 이내 데이터는 RECENT, 오래된 데이터는 STALE, 구간 내 미관측은 NO_DATA, 미설정·조회 실패·불완전 응답은 UNKNOWN입니다. 이는 수집 장애·서비스 정상·무트래픽 판정이 아닙니다. 원본 수신 시각이 아니라 Prometheus 샘플/로그 이벤트/trace 시작 시각을 사용합니다. 저장소 한 곳의 실패도 HTTP 200의 해당 signals 항목에 담으며 다른 결과는 유지합니다. 최대 저장소 timeout 합계(30초)만큼 걸릴 수 있습니다. 설정·마지막 연결 검사 결과는 변경하지 않습니다.")
    @GetMapping
    CollectionStatus status(@PathVariable Long environmentId,
                            @Parameter(description="Deployment 및 service.name과 일치하는 관측 서비스", example="frontend") @RequestParam String service) {
        if (!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))
            throw error(HttpStatus.BAD_REQUEST,"INVALID_QUERY","Use a valid service name");
        var environment=environments.findById(environmentId)
                .orElseThrow(()->error(HttpStatus.NOT_FOUND,"ENVIRONMENT_NOT_FOUND","Environment not found"));
        Instant to=Instant.now().truncatedTo(ChronoUnit.SECONDS),from=to.minusSeconds(LOOKBACK_SECONDS);
        var configured=new EnumMap<DataSourceType,ObservationDataSource>(DataSourceType.class);
        for(var source:sources.findByEnvironmentIdOrderByTypeAsc(environmentId))configured.put(source.type,source);
        var signals=new ArrayList<SignalStatus>();
        var prom=configured.get(DataSourceType.PROMETHEUS);
        if(prom==null) {
            signals.add(missing("deployment_metrics","prometheus","SAMPLE_TIME"));
            signals.add(missing("request_metrics","prometheus","SAMPLE_TIME"));
        } else {
            try { signals.addAll(metrics(prom,environment.getNamespace(),service,from,to)); }
            catch(ResponseStatusException e) {
                signals.add(failed("deployment_metrics","prometheus","SAMPLE_TIME",e));
                signals.add(failed("request_metrics","prometheus","SAMPLE_TIME",e));
            }
        }
        var loki=configured.get(DataSourceType.LOKI);
        if(loki==null)signals.add(missing("logs","loki","LOG_EVENT_TIME"));
        else try { signals.add(logs(loki,environment.getNamespace(),service,from,to)); }
        catch(ResponseStatusException e) { signals.add(failed("logs","loki","LOG_EVENT_TIME",e)); }
        var tempo=configured.get(DataSourceType.TEMPO);
        if(tempo==null)signals.add(missing("traces","tempo","TRACE_START_TIME"));
        else try { signals.add(traces(tempo,environment.getNamespace(),service,from,to)); }
        catch(ResponseStatusException e) { signals.add(failed("traces","tempo","TRACE_START_TIME",e)); }
        return new CollectionStatus(environmentId,environment.getNamespace(),service,from,to,Instant.now(),
                LOOKBACK_SECONDS,FRESHNESS_SECONDS,List.copyOf(signals));
    }

    // Subquery values are original sample times, never query evaluation times.
    static String metricsExpression(String namespace,String service) {
        var selectors=Map.of("deployment_metrics","kube_deployment_status_replicas{namespace=\""+namespace+"\",deployment=\""+service+"\"}",
                "request_metrics","traces_span_metrics_calls_total{service_namespace=\""+namespace+"\",service_name=\""+service+"\",span_kind=\"SPAN_KIND_SERVER\"}");
        var parts=new ArrayList<String>();
        for(var signal:List.of("deployment_metrics","request_metrics")) {
            String raw="timestamp("+selectors.get(signal)+")";
            // Include the instant sample as well as the 30s history grid, including just-started series.
            String latest="max(("+raw+") or (max_over_time("+raw+"[30m:30s])))";
            parts.add("label_replace(("+latest+" >= (time() - 1800)),\"signal\",\""+signal+"\",\"\",\"\")");
        }
        return String.join(" or ",parts);
    }

    private List<SignalStatus> metrics(ObservationDataSource source,String namespace,String service,Instant from,Instant to) {
        var result=prometheus.query(new PrometheusClient.Connection(namespace,source.baseUrl,source.timeoutSeconds),
                Map.of("query",metricsExpression(namespace,service),"time",to.toString()),false);
        var times=new HashMap<String,Instant>();
        for(var row:result.series()) {
            String signal=row.path("metric").path("signal").asText();
            var pair=row.path("value");
            if(!Set.of("deployment_metrics","request_metrics").contains(signal)||times.containsKey(signal)
                    ||!pair.isArray()||pair.size()!=2||!pair.get(0).isNumber()||!pair.get(1).isTextual())throw invalid("PROMETHEUS");
            Instant observed;
            try { observed=Instant.ofEpochSecond(0,new BigDecimal(pair.get(1).asText()).movePointRight(9).longValueExact()); }
            catch(RuntimeException e) { throw invalid("PROMETHEUS"); }
            if(observed.isBefore(from))throw invalid("PROMETHEUS");
            times.put(signal,observed);
        }
        return List.of(observed("deployment_metrics","prometheus","SAMPLE_TIME",times.get("deployment_metrics"),to,result.hasWarnings()),
                observed("request_metrics","prometheus","SAMPLE_TIME",times.get("request_metrics"),to,result.hasWarnings()));
    }

    private SignalStatus logs(ObservationDataSource source,String namespace,String service,Instant from,Instant to) {
        String selector="{k8s_namespace_name="+mapper.writeValueAsString(namespace)+",k8s_deployment_name="+mapper.writeValueAsString(service)+"}";
        var root=http.read(source.baseUrl,source.timeoutSeconds,"/loki/api/v1/query_range",Map.of("query",selector,
                "start",Long.toString(from.getEpochSecond()*1_000_000_000L),"end",Long.toString(to.getEpochSecond()*1_000_000_000L),"limit","1","direction","backward"),false,"LOKI");
        if(!"success".equals(root.path("status").asText())||!"streams".equals(root.path("data").path("resultType").asText()))throw invalid("LOKI");
        var rows=root.path("data").path("result");
        if(!rows.isArray())throw invalid("LOKI");
        Instant latest=null;int count=0;
        for(var row:rows) {
            if(!namespace.equals(row.path("stream").path("k8s_namespace_name").asText())
                    ||!service.equals(row.path("stream").path("k8s_deployment_name").asText())||!row.path("values").isArray())throw invalid("LOKI");
            for(var pair:row.path("values")) {
                if(++count>1||!pair.isArray()||pair.size()<2||!pair.get(0).isTextual()||!pair.get(1).isTextual())throw invalid("LOKI");
                latest=nanos(pair.get(0).asText(),"LOKI");
                if(latest.isBefore(from)||!latest.isBefore(to))throw invalid("LOKI");
            }
        }
        return observed("logs","loki","LOG_EVENT_TIME",latest,to,hasWarnings(root));
    }

    private SignalStatus traces(ObservationDataSource source,String namespace,String service,Instant from,Instant to) {
        String query="{ resource.service.namespace = "+mapper.writeValueAsString(namespace)+" && resource.service.name = "+mapper.writeValueAsString(service)+" } with (most_recent=true)";
        var root=http.read(source.baseUrl,source.timeoutSeconds,"/api/search",Map.of("q",query,"start",Long.toString(from.getEpochSecond()),
                "end",Long.toString(to.getEpochSecond()),"limit","1"),false,"TEMPO");
        var rows=root.path("traces");
        if((!rows.isMissingNode()&&!rows.isArray())||(rows.isMissingNode()&&!root.path("metrics").isObject())||rows.size()>1)throw invalid("TEMPO");
        Instant latest=null;
        for(var row:rows) {
            String id=row.path("traceID").asText();
            if(!id.matches("[0-9a-f]{1,32}")||id.matches("0+"))throw invalid("TEMPO");
            if(!hasMatchingSpan(row,namespace,service))throw invalid("TEMPO");
            latest=nanos(row.path("startTimeUnixNano").asText(),"TEMPO");
        }
        var stats=root.path("metrics");
        boolean partial=hasWarnings(root)||"PARTIAL".equals(root.path("status").asText());
        if(stats.has("totalJobs")||stats.has("completedJobs")) {
            // Protobuf omits zero counters; still detect searches that did not inspect all jobs.
            long total=counter(stats,"totalJobs"),completed=counter(stats,"completedJobs");
            if(completed>total)throw invalid("TEMPO");
            partial|=completed<total;
        }
        var result=observed("traces","tempo","TRACE_START_TIME",latest,to,partial);
        // Tempo may find an overlapping trace whose root predates the requested window.
        if(latest!=null&&latest.isBefore(from)) {
            var warnings=new ArrayList<>(result.warnings());warnings.add("TRACE_START_OUTSIDE_WINDOW");
            return new SignalStatus("traces","tempo",result.queryStatus(),"UNKNOWN",latest,result.ageSeconds(),"TRACE_START_TIME",null,List.copyOf(warnings));
        }
        return result;
    }

    private static boolean hasMatchingSpan(JsonNode trace,String namespace,String service) {
        var sets=trace.path("spanSets");
        var candidates=new ArrayList<JsonNode>();
        if(sets.isArray())sets.forEach(candidates::add);
        else if(trace.path("spanSet").isObject())candidates.add(trace.path("spanSet"));
        for(var set:candidates)for(var span:set.path("spans")) {
            var attrs=new HashMap<String,String>();
            for(var attr:span.path("attributes"))attrs.put(attr.path("key").asText(),attr.path("value").path("stringValue").asText());
            if(namespace.equals(attrs.get("service.namespace"))&&service.equals(attrs.get("service.name")))return true;
        }
        return false;
    }
    private static long counter(JsonNode stats,String key) {
        if(!stats.has(key))return 0;
        try { long n=Long.parseLong(stats.path(key).asText());if(n<0)throw new NumberFormatException();return n; }
        catch(NumberFormatException e){throw invalid("TEMPO");}
    }
    private static boolean hasWarnings(JsonNode root){return root.path("warnings").size()>0||root.path("infos").size()>0;}
    private static Instant nanos(String value,String source) {
        try { long n=Long.parseLong(value);if(n<0)throw new NumberFormatException();return Instant.ofEpochSecond(0,n); }
        catch(RuntimeException e){throw invalid(source);}
    }
    static SignalStatus observed(String signal,String source,String kind,Instant observed,Instant at,boolean partial) {
        var warnings=new ArrayList<String>();
        if(kind.equals("SAMPLE_TIME"))warnings.add("REPRESENTATIVE_SERIES_ONLY");
        else warnings.add("EVENT_TIME_NOT_INGESTION_TIME");
        if(signal.equals("request_metrics"))warnings.add("SCRAPE_TIME_NOT_REQUEST_ACTIVITY");
        if(partial)warnings.add("UPSTREAM_PARTIAL");
        boolean future=observed!=null&&observed.isAfter(at);
        if(future)warnings.add("FUTURE_TIMESTAMP");
        Long age=observed==null||future?null:Duration.between(observed,at).getSeconds();
        String freshness=partial||future?"UNKNOWN":observed==null?"NO_DATA":!observed.isBefore(at.minusSeconds(FRESHNESS_SECONDS))?"RECENT":"STALE";
        if(freshness.equals("NO_DATA")||freshness.equals("STALE"))warnings.add("CAUSE_UNDETERMINED");
        return new SignalStatus(signal,source,partial?"PARTIAL":"OK",freshness,observed,age,kind,null,List.copyOf(warnings));
    }
    private static SignalStatus missing(String signal,String source,String kind) {
        return new SignalStatus(signal,source,"NOT_CONFIGURED","UNKNOWN",null,null,kind,null,List.of());
    }
    private static SignalStatus failed(String signal,String source,String kind,ResponseStatusException e) {
        if(e.getStatusCode().value()==503)throw e;
        var props=e.getBody().getProperties();
        String code=props!=null&&props.get("code") instanceof String value?value:"QUERY_FAILED";
        return new SignalStatus(signal,source,"ERROR","UNKNOWN",null,null,kind,code,List.of());
    }
    private static ResponseStatusException invalid(String source){return error(HttpStatus.BAD_GATEWAY,"INVALID_"+source+"_RESPONSE","Unexpected observation response");}
    record CollectionStatus(Long environmentId,String namespace,String service,Instant from,Instant to,Instant checkedAt,
                            int lookbackSeconds,int freshnessThresholdSeconds,List<SignalStatus> signals) {}
    record SignalStatus(String signal,String sourceType,String queryStatus,String freshness,Instant observedAt,Long ageSeconds,
                        String timestampKind,String failureCode,List<String> warnings) {}
}
