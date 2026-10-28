package com.sentrix.core.datasource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@Tag(name="03. 서비스와 메트릭")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/service-map")
class ServiceMapController {
    private final TraceController traces;
    ServiceMapController(TraceController traces) { this.traces=traces; }

    @Operation(summary="요청 하나의 서비스 호출 관계", description="traceId는 필수 32자리 소문자 hex입니다. 현재 환경의 trace에서 직접 연결된 CLIENT 부모 → SERVER 자식만 집계합니다. 전체 트래픽의 서비스 맵·초당 호출량·오류율이 아닙니다. 서비스명은 trace resource 기준이며 Deployment 이름으로 추정하지 않습니다. missing parent, 미계측, 비동기 호출은 완전한 관계로 복원하지 않습니다. 서버 span의 처리 시간과 명시적 ERROR 기록을 제공합니다. UNSET은 성공으로 판정하지 않습니다.")
    @GetMapping
    ServiceMap get(@PathVariable Long environmentId,@RequestParam String traceId) {
        return build(traces.detail(environmentId,traceId));
    }

    static ServiceMap build(TraceController.TraceDetail trace) {
        var byId=new HashMap<String,TraceController.Span>();
        var nodes=new TreeSet<String>();
        for(var span:trace.spans()) {byId.put(span.spanId(),span);nodes.add(span.service());}
        var edges=new TreeMap<String,TreeMap<String,List<TraceController.Span>>>();
        var warnings=new LinkedHashSet<>(trace.warnings());
        warnings.add("SINGLE_TRACE_ONLY");
        warnings.add("DIRECT_CLIENT_SERVER_PAIRS_ONLY");
        for(var span:trace.spans()) {
            if(!span.kind().equals("SERVER"))continue;
            var parent=byId.get(span.parentSpanId());
            if(parent==null || !parent.kind().equals("CLIENT"))continue;
            if(parent.service().equals("unknown") || span.service().equals("unknown")) {
                warnings.add("UNIDENTIFIED_SERVICE_EDGE_OMITTED");continue;
            }
            edges.computeIfAbsent(parent.service(),k->new TreeMap<>())
                    .computeIfAbsent(span.service(),k->new ArrayList<>()).add(span);
        }
        var result=new ArrayList<Edge>();
        edges.forEach((source,targets)->targets.forEach((target,spans)->result.add(new Edge(source,target,spans.size(),
                spans.stream().filter(s->s.status().equals("ERROR")).count(),
                spans.stream().filter(s->s.status().equals("UNSET")).count(),
                spans.stream().mapToDouble(TraceController.Span::durationMs).average().orElseThrow(),
                spans.stream().mapToDouble(TraceController.Span::durationMs).max().orElseThrow()))));
        return new ServiceMap(trace.environmentId(),trace.namespace(),trace.traceId(),"SINGLE_TRACE",
                result.isEmpty()?"NO_OBSERVED_EDGES":"PARTIAL",List.copyOf(warnings),List.copyOf(nodes),List.copyOf(result));
    }
    record Edge(String source,String target,int observedCalls,long explicitErrorCount,long unsetStatusCount,
                double meanServerDurationMs,double maxServerDurationMs) {}
    record ServiceMap(Long environmentId,String namespace,String traceId,String scope,String dataStatus,
                      List<String>warnings,List<String>nodes,List<Edge>edges) {}
}
