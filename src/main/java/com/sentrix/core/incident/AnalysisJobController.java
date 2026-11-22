package com.sentrix.core.incident;

import com.sentrix.core.analysis.AnalysisProtocol;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@Tag(name="07. 장애 기록과 분석 작업")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/incidents/{incidentId}/rca-jobs")
class AnalysisJobController {
    private final AnalysisJobs jobs;
    AnalysisJobController(AnalysisJobs jobs) { this.jobs=jobs; }
    record JobInput(@NotNull UUID requestKey, Instant windowEnd, Boolean latest) {}

    @Operation(summary="RCA 분석 요청 접수", description="기본은 모델 미연결 대기입니다. 운영자가 계약·모델·feature 버전을 설정한 경우 새 요청만 실행 큐에 넣습니다. windowEnd 생략 시 장애 발생 시각이 속한 UTC 30초 구간을 고정합니다. latest=true이면 서버 시각 기준 직전 10초 경계에서 끝나는 최근 30초를 고정합니다. windowEnd와 latest=true는 동시에 지정할 수 없습니다. 직접 지정하면 과거의 10초 격자 끝 시각이어야 합니다. 수집 대기 30초 후 실행합니다. 동일 키 재전송은 기존 작업, 다른 구간은 409입니다. 장애당 활성 작업 하나만 허용합니다.")
    @PostMapping
    AnalysisJobs.Job create(@PathVariable Long environmentId,@PathVariable UUID incidentId,@Valid @RequestBody JobInput input) {
        return jobs.create(environmentId,incidentId,input.requestKey(),input.windowEnd(),Boolean.TRUE.equals(input.latest()));
    }
    @Operation(summary="장애별 분석 작업 이력", description="미연결 대기·실행 대기·실행 중·성공·실패·취소 이력을 최근 요청부터 조회합니다. 기본 모델은 미연결입니다.")
    @GetMapping
    AnalysisJobs.JobPage list(@PathVariable Long environmentId,@PathVariable UUID incidentId,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size) {
        return jobs.list(environmentId,incidentId,page,size);
    }
    @Operation(summary="분석 작업 상태 상세", description="고정된 30초 분석 구간, 실행·종료 시각, 계약·모델 버전, 실패 사유를 반환합니다. 과거 작업의 추가 메타데이터는 null입니다.")
    @GetMapping("/{jobId}")
    AnalysisJobs.Job get(@PathVariable Long environmentId,@PathVariable UUID incidentId,@PathVariable UUID jobId) {
        return jobs.get(environmentId,incidentId,jobId);
    }
    @Operation(summary="분석 작업 취소", description="미연결·실행 대기·실행 중 작업을 취소합니다. 실행 중 원격 계산은 종료를 보장하지 않지만 늦게 온 응답은 저장하지 않습니다. 완료·실패·취소 작업의 반복 취소는 상태를 바꾸지 않습니다.")
    @PostMapping("/{jobId}/cancel")
    AnalysisJobs.Job cancel(@PathVariable Long environmentId,@PathVariable UUID incidentId,@PathVariable UUID jobId) {
        return jobs.cancel(environmentId,incidentId,jobId);
    }
    @Operation(summary="분석 작업 결과 조회", description="검증을 통과해 저장된 성공 작업의 응답만 반환합니다. 성공은 요청 처리 성공이며 장애 발생 판정과 다릅니다. decision은 ANOMALY/NORMAL/ABSTAIN이며 score를 확률로 해석하지 않습니다. 미완료·실패·취소·미연결 작업은 409입니다. 판정 보류에도 실제 원시 점수가 포함될 수 있습니다.")
    @GetMapping("/{jobId}/result")
    AnalysisProtocol.Response result(@PathVariable Long environmentId,@PathVariable UUID incidentId,@PathVariable UUID jobId) {
        return jobs.result(environmentId,incidentId,jobId);
    }
}
