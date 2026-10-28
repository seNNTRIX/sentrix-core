package com.sentrix.core.monitoring;

import com.sentrix.core.environment.EnvironmentRepository;
import com.sentrix.core.datasource.PrometheusClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Tag(name="06. 분석 실행 설정")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/monitoring")
class MonitoringController {
    private final EnvironmentRepository environments;
    private final JdbcTemplate db;
    MonitoringController(EnvironmentRepository environments,JdbcTemplate db){this.environments=environments;this.db=db;}
    @Operation(summary="분석 실행 설정 조회", description="모델 미연결 상태입니다. 활성화 요청은 저장되지만 분석 실행·장애 판정은 수행하지 않습니다. 설정이 없으면 비활성 기본값입니다.")
    @GetMapping
    State get(@PathVariable Long environmentId){
        check(environmentId);
        var rows=db.query("SELECT enabled, interval_seconds, updated_at FROM monitoring_settings WHERE environment_id=?",(rs,n)->state(environmentId,rs.getBoolean(1),rs.getInt(2),rs.getTimestamp(3).toInstant()),environmentId);
        return rows.isEmpty()?state(environmentId,false,60,null):rows.getFirst();
    }
    @Operation(summary="분석 실행 의도 저장", description="enabled와 intervalSeconds(30~3600)를 저장합니다. 모델 미연결이므로 enabled=true여도 BLOCKED_MODEL_UNAVAILABLE이며 실제 실행 성공을 의미하지 않습니다.")
    @PutMapping
    @Transactional
    State put(@PathVariable Long environmentId,@Valid @RequestBody Settings input){
        check(environmentId);
        // Serialize first insert and subsequent updates using the existing parent row.
        db.queryForObject("SELECT id FROM environments WHERE id=? FOR UPDATE",Long.class,environmentId);
        Instant now=Instant.now();
        if(db.update("UPDATE monitoring_settings SET enabled=?, interval_seconds=?, updated_at=? WHERE environment_id=?",input.enabled(),input.intervalSeconds(),java.sql.Timestamp.from(now),environmentId)==0)
            db.update("INSERT INTO monitoring_settings(environment_id,enabled,interval_seconds,updated_at) VALUES(?,?,?,?)",environmentId,input.enabled(),input.intervalSeconds(),java.sql.Timestamp.from(now));
        return state(environmentId,input.enabled(),input.intervalSeconds(),now);
    }
    private void check(Long id){if(!environments.existsById(id))throw PrometheusClient.error(HttpStatus.NOT_FOUND,"ENVIRONMENT_NOT_FOUND","Environment not found");}
    private State state(Long id,boolean enabled,int interval,Instant updated){return new State(id,enabled,interval,enabled?"BLOCKED_MODEL_UNAVAILABLE":"DISABLED","UNAVAILABLE",null,updated);}
    record Settings(@NotNull Boolean enabled,@NotNull @Min(30) @Max(3600) Integer intervalSeconds){}
    record State(Long environmentId,boolean enabled,int intervalSeconds,String executionStatus,String modelStatus,Instant lastSuccessfulAt,Instant updatedAt){}
}
