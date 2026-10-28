package com.sentrix.core.telemetry;

import com.sentrix.core.datasource.PrometheusClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name="03. 서비스와 메트릭")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/services/{service}/pods")
class PodController {
    private final PrometheusClient prometheus;
    PodController(PrometheusClient prometheus){this.prometheus=prometheus;}
    static String fresh(String selector){return "("+selector+" and (timestamp("+selector+") >= time() - 60))";}
    static String members(String namespace,String service){
        String ns="namespace=\""+namespace+"\"";
        String owners="max by(namespace,pod,uid,replicaset)(label_replace("+fresh("kube_pod_owner{"+ns+",owner_kind=\"ReplicaSet\",owner_is_controller=\"true\"}")+",\"replicaset\",\"$1\",\"owner_name\",\"(.+)\"))";
        String rs="max by(namespace,replicaset)("+fresh("kube_replicaset_owner{"+ns+",owner_kind=\"Deployment\",owner_is_controller=\"true\",owner_name=\""+service+"\"}")+")";
        return "max by(namespace,pod,uid)(("+owners+" * on(namespace,replicaset) group_left() "+rs+") == 1)";
    }
    static String expression(String namespace,String service){
        String ns="namespace=\""+namespace+"\"";
        String members=members(namespace,service);
        String ready="min by(namespace,pod,uid)("+fresh("kube_pod_status_ready{"+ns+",condition=\"true\"}")+")";
        String restarts="sum by(namespace,pod,uid)(max by(namespace,pod,uid,container)("+fresh("kube_pod_container_status_restarts_total{"+ns+"}")+"))";
        return field(members,"member")+" or "+field("("+ready+" and on(namespace,pod,uid) "+members+")","ready")+" or "+field("("+restarts+" and on(namespace,pod,uid) "+members+")","restarts");
    }
    private static String field(String expression,String name){return "label_replace("+expression+",\"field\",\""+name+"\",\"\",\"\")";}
    @Operation(summary="서비스 소속 Pod 조회",description="서비스는 Deployment 이름입니다. Pod→ReplicaSet→Deployment 소유 관계로 조회하며 60초 이내 샘플만 사용합니다. 최대 500 Pod. 준비 상태는 Kubernetes Ready 조건이며 요청 성공/ML 정상 판정이 아닙니다. 재시작 수는 현재 관측된 일반 컨테이너 합계이며 init 컨테이너는 제외합니다. 결측은 null, 빈 목록은 미관측이며 서비스 부재를 확정하지 않습니다.")
    @GetMapping
    PodList get(@PathVariable Long environmentId,@PathVariable String service){
        if(!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))throw PrometheusClient.error(HttpStatus.BAD_REQUEST,"INVALID_QUERY","Invalid service name");
        var connection=prometheus.connection(environmentId);Instant now=Instant.now();
        var result=prometheus.query(connection,Map.of("query",expression(connection.namespace(),service),"time",now.toString()),false);
        if(result.series().size()>1500)throw PrometheusClient.error(HttpStatus.BAD_GATEWAY,"TOO_MANY_PODS","Pod inventory exceeds limit");
        var rows=new TreeMap<String,Map<String,Long>>();var names=new HashMap<String,String>();
        for(var row:result.series()){
            var labels=row.path("metric");String pod=labels.path("pod").asText(),uid=labels.path("uid").asText(),field=labels.path("field").asText();
            var pair=row.path("value");
            if(!connection.namespace().equals(labels.path("namespace").asText())||!pod.matches("[a-z0-9][a-z0-9.-]{0,252}")||uid.isBlank()||uid.length()>128||!Set.of("member","ready","restarts").contains(field)||!pair.isArray()||pair.size()!=2||!pair.get(0).isNumber()||!pair.get(1).isTextual())throw PrometheusClient.invalidResponse();
            long value;try{value=new java.math.BigDecimal(pair.get(1).asText()).longValueExact();}catch(RuntimeException e){throw PrometheusClient.invalidResponse();}
            if(value<0||(!field.equals("restarts")&&value>1)||field.equals("member")&&value!=1)throw PrometheusClient.invalidResponse();
            String key=pod+"/"+uid;names.put(key,pod);
            if(rows.computeIfAbsent(key,k->new HashMap<>()).put(field,value)!=null)throw PrometheusClient.invalidResponse();
        }
        var items=new ArrayList<Pod>();var warnings=new LinkedHashSet<String>();if(result.hasWarnings())warnings.add("UPSTREAM_WARNING");
        for(var entry:rows.entrySet()){
            var values=entry.getValue();if(!values.containsKey("member"))throw PrometheusClient.invalidResponse();
            if(values.size()<3)warnings.add("MISSING_POD_METRICS");
            items.add(new Pod(names.get(entry.getKey()),entry.getKey().substring(entry.getKey().indexOf('/')+1),values.containsKey("ready")?values.get("ready")==1:null,values.get("restarts")));
        }
        if(items.size()>500)throw PrometheusClient.error(HttpStatus.BAD_GATEWAY,"TOO_MANY_PODS","Pod inventory exceeds limit");
        warnings.add("CURRENT_SAMPLES_ONLY");
        return new PodList(environmentId,connection.namespace(),service,now,60,items.isEmpty()?"NO_DATA":warnings.size()>1?"PARTIAL":"AVAILABLE",List.copyOf(warnings),List.copyOf(items));
    }
    record Pod(String name,String uid,Boolean ready,Long restarts){}
    record PodList(Long environmentId,String namespace,String service,Instant queriedAt,int maxAgeSeconds,String dataStatus,List<String>warnings,List<Pod>items){}
}
