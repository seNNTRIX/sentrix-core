package com.sentrix.core.datasource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.sentrix.core.environment.EnvironmentRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Tag(name="02. 데이터 소스")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/data-sources")
class DataSourceController {
    private final EnvironmentRepository environments;
    private final ObservationDataSourceRepository sources;
    private final DataSourceTargetPolicy policy;
    private final DataSourceProbe probe;

    DataSourceController(EnvironmentRepository environments, ObservationDataSourceRepository sources,
                         DataSourceTargetPolicy policy, DataSourceProbe probe) {
        this.environments = environments;
        this.sources = sources;
        this.policy = policy;
        this.probe = probe;
    }

    @Operation(summary="데이터 소스 설정 조회", description="종류별 접속 설정과 마지막 연결 검사 결과입니다. 지속적인 수집 상태를 뜻하지 않습니다.")
    @GetMapping
    List<DataSourceResponse> list(@PathVariable Long environmentId) {
        requireEnvironment(environmentId);
        return sources.findByEnvironmentIdOrderByTypeAsc(environmentId).stream()
                .map(DataSourceResponse::from).toList();
    }

    @Operation(summary="데이터 소스 설정 저장", description="name(1~100자), baseUrl(최대 2048자), timeoutSeconds(정수 1~10)를 입력합니다. 서버 허용 목록의 주소만 저장합니다. 저장만으로 연결 검사를 실행하지 않습니다.")
    @PutMapping("/{type}")
    DataSourceResponse configure(@PathVariable Long environmentId, @Parameter(schema=@Schema(allowableValues={"prometheus","loki","tempo"})) @PathVariable String type,
                                 @Valid @RequestBody ConfigureDataSource request) {
        var sourceType = DataSourceType.parse(type);
        requireEnvironment(environmentId);
        String url = policy.validate(request.baseUrl());
        var source = sources.findByEnvironmentIdAndType(environmentId, sourceType)
                .orElseGet(() -> new ObservationDataSource(environmentId, sourceType));
        source.configure(request.name(), url, request.timeoutSeconds());
        return persist(source);
    }

    @Operation(summary="데이터 소스 연결 테스트", description="저장된 주소의 readiness를 확인합니다. 연결 실패도 검사가 끝났으면 HTTP 200과 status=FAILED로 반환합니다. CONNECTED는 데이터 수집 정상 판정이 아닙니다.")
    @PostMapping("/{type}/test")
    DataSourceResponse test(@PathVariable Long environmentId, @Parameter(schema=@Schema(allowableValues={"prometheus","loki","tempo"})) @PathVariable String type) {
        var sourceType = DataSourceType.parse(type);
        requireEnvironment(environmentId);
        var source = sources.findByEnvironmentIdAndType(environmentId, sourceType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Data source is not configured"));
        // No DB transaction is held while waiting on the remote server.
        source.checked(probe.check(source));
        return persist(source);
    }

    private DataSourceResponse persist(ObservationDataSource source) {
        try {
            return DataSourceResponse.from(sources.saveAndFlush(source));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Data source changed concurrently; reload and retry");
        }
    }

    private void requireEnvironment(Long id) {
        if (!environments.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Environment not found");
        }
    }

    record ConfigureDataSource(@NotBlank @Size(max = 100) String name,
                               @NotBlank @Size(max = 2048) String baseUrl,
                               @NotNull @Min(1) @Max(10) Integer timeoutSeconds) {
        ConfigureDataSource {
            name = name == null ? null : name.strip();
            baseUrl = baseUrl == null ? null : baseUrl.strip();
        }
    }

    record DataSourceResponse(Long id, Long environmentId, String type, String name, String baseUrl,
                              int timeoutSeconds, String status, Instant lastCheckedAt, Long latencyMs,
                              Integer httpStatus, String failureCode, Instant createdAt, Instant updatedAt) {
        static DataSourceResponse from(ObservationDataSource s) {
            return new DataSourceResponse(s.id, s.environmentId, s.type.name().toLowerCase(Locale.ROOT), s.name,
                    s.baseUrl, s.timeoutSeconds, s.status, s.lastCheckedAt, s.latencyMs, s.httpStatus,
                    s.failureCode, s.createdAt, s.updatedAt);
        }
    }
}
