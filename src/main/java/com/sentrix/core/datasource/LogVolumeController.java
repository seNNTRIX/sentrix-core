package com.sentrix.core.datasource;

import com.sentrix.core.environment.EnvironmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static com.sentrix.core.datasource.PrometheusClient.error;

@Tag(name="04. 로그")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/logs/volume")
class LogVolumeController {
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceHttpClient http;
    private final ObjectMapper mapper;
    LogVolumeController(EnvironmentRepository environments, ObservationDataSourceRepository sources, DataSourceHttpClient http, ObjectMapper mapper) {
        this.environments=environments; this.sources=sources; this.http=http; this.mapper=mapper;
    }

    @Operation(summary="시간별 로그 건수 집계", description="service/from/to 필수. Loki count_over_time으로 검색 결과 상한 없이 집계합니다. UTC 초 단위, 최대 24시간, bucketSeconds 60~3600(기본 60), 최대 720구간이며 조회 길이는 버킷 크기의 배수여야 합니다. 로그 검색 API와 달리 각 구간은 시작 제외·끝 포함 (from,to]입니다. search는 최대 200자 원문 포함 검색. 결측은 null이며 로그 없음과 수집 중단을 구분하지 못합니다. 건수는 저장된 로그 행 수이며 요청 수·장애 수가 아닙니다.")
    @GetMapping
    Result volume(@PathVariable Long environmentId,@RequestParam String service,@RequestParam Instant from,@RequestParam Instant to,
                  @RequestParam(defaultValue="60") int bucketSeconds,@RequestParam(defaultValue="") String search) {
        if(!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?") || search.length()>200 || search.chars().anyMatch(Character::isISOControl)
                || from.getNano()!=0 || to.getNano()!=0 || from.isBefore(Instant.EPOCH) || !from.isBefore(to)
                || Duration.between(from,to).compareTo(Duration.ofHours(24))>0 || bucketSeconds<60 || bucketSeconds>3600)
            throw bad("Use whole seconds, up to 24 hours, bucketSeconds 60..3600 and a valid service/search");
        long duration=Duration.between(from,to).getSeconds(), count=duration/bucketSeconds;
        if(duration%bucketSeconds!=0 || count>720) throw bad("Range must be a multiple of bucketSeconds, at most 720 buckets");
        try {Math.multiplyExact(to.getEpochSecond(),1_000_000_000L);} catch(ArithmeticException e){throw bad("Timestamp outside supported range");}
        var env=environments.findById(environmentId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"ENVIRONMENT_NOT_FOUND","Environment not found"));
        var source=sources.findByEnvironmentIdAndType(environmentId,DataSourceType.LOKI)
                .orElseThrow(()->error(HttpStatus.CONFLICT,"LOKI_NOT_CONFIGURED","Configure Loki first"));
        String selector="{k8s_namespace_name="+mapper.writeValueAsString(env.getNamespace())+",k8s_deployment_name="+mapper.writeValueAsString(service)+"}";
        if(!search.isEmpty()) selector+=" |= "+mapper.writeValueAsString(search);
        String query="sum by(k8s_namespace_name,k8s_deployment_name)(count_over_time("+selector+"["+bucketSeconds+"s]))";
        var root=http.read(source.baseUrl,source.timeoutSeconds,"/loki/api/v1/query_range",
                Map.of("query",query,"start",from.plusSeconds(bucketSeconds).toString(),"end",to.toString(),"step",String.valueOf(bucketSeconds)),false,"LOKI");
        if(!"success".equals(root.path("status").asText())) throw error(HttpStatus.BAD_GATEWAY,"LOKI_QUERY_FAILED","Loki could not execute the query");
        var data=root.path("data");var rows=data.path("result");
        if(!"matrix".equals(data.path("resultType").asText()) || !rows.isArray() || rows.size()>1) throw invalid();
        var values=new HashMap<Instant,Long>();
        for(var row:rows) {
            var labels=row.path("metric");var samples=row.path("values");
            if(!env.getNamespace().equals(labels.path("k8s_namespace_name").asText()) || !service.equals(labels.path("k8s_deployment_name").asText())
                    || !samples.isArray() || samples.size()>count) throw invalid();
            for(var sample:samples) {
                if(!sample.isArray() || sample.size()!=2 || !sample.get(0).isNumber() || !sample.get(1).isTextual()) throw invalid();
                Instant time;long value;
                try {time=Instant.ofEpochSecond(new BigDecimal(sample.get(0).asText()).longValueExact());value=new BigDecimal(sample.get(1).asText()).longValueExact();}
                catch(RuntimeException e){throw invalid();}
                if(!time.isAfter(from)||time.isAfter(to)||(time.getEpochSecond()-from.getEpochSecond())%bucketSeconds!=0||value<0||values.putIfAbsent(time,value)!=null) throw invalid();
            }
        }
        var warnings=new ArrayList<String>();
        if(root.path("warnings").size()>0||root.path("infos").size()>0) warnings.add("UPSTREAM_WARNING");
        String status=values.isEmpty()?"NO_DATA":values.size()<count||!warnings.isEmpty()?"PARTIAL":"AVAILABLE";
        warnings.add("COLLECTION_COMPLETENESS_UNKNOWN");
        var buckets=new ArrayList<Bucket>();
        for(long i=0;i<count;i++) {
            Instant start=from.plusSeconds(i*bucketSeconds),end=start.plusSeconds(bucketSeconds);
            buckets.add(new Bucket(start,end,values.get(end)));
        }
        return new Result(environmentId,env.getNamespace(),service,from,to,bucketSeconds,search,"START_EXCLUSIVE_END_INCLUSIVE",status,List.copyOf(warnings),List.copyOf(buckets));
    }
    private static org.springframework.web.server.ResponseStatusException bad(String detail){return error(HttpStatus.BAD_REQUEST,"INVALID_QUERY",detail);}
    private static org.springframework.web.server.ResponseStatusException invalid(){return error(HttpStatus.BAD_GATEWAY,"INVALID_LOKI_RESPONSE","Unexpected Loki volume response");}
    record Bucket(Instant from,Instant to,Long count){}
    record Result(Long environmentId,String namespace,String service,Instant from,Instant to,int bucketSeconds,String search,
                  String intervalBounds,String dataStatus,List<String>warnings,List<Bucket>buckets){}
}
