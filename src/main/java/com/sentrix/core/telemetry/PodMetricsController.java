package com.sentrix.core.telemetry;

import com.sentrix.core.datasource.PrometheusClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name="03. 서비스와 메트릭")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/services/{service}/pods/{pod}/metrics")
class PodMetricsController {
    private final PrometheusClient prometheus;
    PodMetricsController(PrometheusClient prometheus) { this.prometheus = prometheus; }

    static String expression(String namespace, String service, String pod, String uid, String metric) {
        // cgroupfs and systemd paths both contain the immutable Pod UID.
        String identity = ".*pod(" + uid + "|" + uid.replace('-', '_') + ")(/|\\\\.slice/).*";
        String selector = "{namespace=\"" + namespace + "\",pod=\"" + pod
                + "\",container!=\"\",container!=\"POD\",id=~\"" + identity + "\"}";
        String raw = metric.equals("cpu_usage_cores") ? "container_cpu_usage_seconds_total" : "container_memory_working_set_bytes";
        String usage = metric.equals("cpu_usage_cores")
                ? "(rate(" + raw + selector + "[1m]) and " + PodController.fresh(raw + selector) + ")"
                : PodController.fresh(raw + selector);
        String owners = "(" + PodController.members(namespace, service)
                + " and on(namespace,pod,uid) " + PodController.fresh("kube_pod_info{namespace=\"" + namespace
                + "\",pod=\"" + pod + "\",uid=\"" + uid + "\"}") + ")";
        return "sum by(namespace,pod)(max by(namespace,pod,container)(" + usage
                + ")) * on(namespace,pod) group_left(uid) " + owners;
    }

    @Operation(summary="Pod CPU·메모리 시계열 조회", description="Pod 목록의 uid가 필수입니다. 각 조회 시점의 Deployment 소속과 cAdvisor cgroup 경로의 UID를 검증합니다. cgroupfs/systemd 경로만 지원합니다. from 포함·to 제외, UTC 초 단위, 최대 24시간·1440지점, stepSeconds 5~3600. CPU는 1분 rate(cores), 메모리는 working set(bytes). 미수집·소속 미확인은 null이며 0과 구분합니다. 관측된 컨테이너 합계로 전체 컨테이너 수집 완전성을 보장하지 않습니다.")
    @GetMapping
    Result get(@PathVariable Long environmentId, @PathVariable String service, @PathVariable String pod,
               @RequestParam String uid,
               @Parameter(schema=@Schema(allowableValues={"cpu_usage_cores","memory_working_set_bytes"})) @RequestParam String metric,
               @RequestParam Instant from, @RequestParam Instant to, @RequestParam(defaultValue="30") int stepSeconds) {
        if (!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                || !pod.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                || !uid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw bad("Invalid service, pod or uid");
        if (!Set.of("cpu_usage_cores", "memory_working_set_bytes").contains(metric)) throw bad("Unsupported Pod metric");
        if (from.getNano()!=0 || to.getNano()!=0 || !from.isBefore(to)
                || Duration.between(from,to).compareTo(Duration.ofHours(24))>0 || stepSeconds<5 || stepSeconds>3600) throw bad("Use whole seconds, from < to, up to 24 hours and stepSeconds 5..3600");
        long count=(Duration.between(from,to).getSeconds()-1)/stepSeconds+1;
        if(count>1440) throw bad("At most 1440 points");
        var connection=prometheus.connection(environmentId);
        Instant last=from.plusSeconds((count-1)*stepSeconds);
        var response=prometheus.query(connection,Map.of("query",expression(connection.namespace(),service,pod,uid,metric),
                "start",from.toString(),"end",last.toString(),"step",String.valueOf(stepSeconds)),true);
        if(response.series().size()>1) throw PrometheusClient.invalidResponse();
        var values=new HashMap<Instant,Double>();
        var warnings=new ArrayList<String>();
        if(response.hasWarnings()) warnings.add("UPSTREAM_WARNING");
        for(var series:response.series()) {
            var labels=series.path("metric");var samples=series.path("values");
            if(!connection.namespace().equals(labels.path("namespace").asText()) || !pod.equals(labels.path("pod").asText())
                    || !uid.equals(labels.path("uid").asText()) || !samples.isArray() || samples.size()>count) throw PrometheusClient.invalidResponse();
            for(var sample:samples) {
                if(!sample.isArray() || sample.size()!=2 || !sample.get(0).isNumber() || !sample.get(1).isTextual()) throw PrometheusClient.invalidResponse();
                Instant time; Double value;
                try {
                    time=Instant.ofEpochSecond(new java.math.BigDecimal(sample.get(0).asText()).longValueExact());
                    String raw=sample.get(1).asText();
                    value=Set.of("NaN","+Inf","-Inf").contains(raw)?null:Double.valueOf(raw);
                } catch(RuntimeException e) {throw PrometheusClient.invalidResponse();}
                if(time.isBefore(from)||time.isAfter(last)||(time.getEpochSecond()-from.getEpochSecond())%stepSeconds!=0
                        ||values.containsKey(time)||value!=null&&(!Double.isFinite(value)||value<0)) throw PrometheusClient.invalidResponse();
                if(value==null&&!warnings.contains("NON_FINITE_VALUES")) warnings.add("NON_FINITE_VALUES");
                values.put(time,value);
            }
        }
        var points=new ArrayList<TelemetryController.Point>();int available=0;
        for(long i=0;i<count;i++) {
            Instant time=from.plusSeconds(i*stepSeconds);Double value=values.get(time);
            if(value!=null) available++;
            points.add(new TelemetryController.Point(time,value));
        }
        String status=available==0?"NO_DATA":available<count||!warnings.isEmpty()?"PARTIAL":"AVAILABLE";
        warnings.add("OBSERVED_CONTAINERS_ONLY");
        return new Result(environmentId,connection.namespace(),service,pod,uid,metric,metric.equals("cpu_usage_cores")?"cores":"bytes",
                from,to,stepSeconds,status,List.copyOf(warnings),List.copyOf(points));
    }
    private static org.springframework.web.server.ResponseStatusException bad(String detail) {
        return PrometheusClient.error(HttpStatus.BAD_REQUEST,"INVALID_QUERY",detail);
    }
    record Result(Long environmentId,String namespace,String service,String pod,String uid,String metric,String unit,
                  Instant from,Instant to,int stepSeconds,String dataStatus,List<String>warnings,List<TelemetryController.Point>points) {}
}
