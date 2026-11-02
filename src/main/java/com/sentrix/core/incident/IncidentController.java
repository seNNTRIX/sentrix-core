package com.sentrix.core.incident;

import com.sentrix.core.datasource.PrometheusClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Tag(name="07. 장애 기록과 분석 작업")
@RestController
@RequestMapping("/api/v1/environments/{environmentId}/incidents")
class IncidentController {
 private final JdbcTemplate db;
 IncidentController(JdbcTemplate db){this.db=db;}
 enum IncidentStatus { OPEN, RESOLVED }
 record IncidentInput(@NotNull UUID requestKey,
  @NotBlank @Size(max=253) @Pattern(regexp="[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?") String service,
  @NotBlank @Size(max=200) String title,@NotNull @Size(max=2000) String description,
  @NotNull @PastOrPresent Instant occurredAt) {}
 record IncidentUpdate(@NotNull IncidentStatus status) {}
 record JobInput(@NotNull UUID requestKey) {}
 record IncidentView(String id,Long environmentId,String source,String service,String title,String description,
  Instant occurredAt,Instant createdAt,IncidentStatus status,Instant resolvedAt) {}
 record AnalysisJobView(String id,String incidentId,Long environmentId,String status,String reason,
  Instant createdAt,Instant updatedAt) {}
 record IncidentPage(List<IncidentView> items,int page,int size,long totalElements,long totalPages) {}
 record AnalysisJobPage(List<AnalysisJobView> items,int page,int size,long totalElements,long totalPages) {}
 private static Timestamp ts(Instant t){return Timestamp.from(t.truncatedTo(ChronoUnit.MICROS));}
 private static Instant instant(java.sql.ResultSet r,String name)throws java.sql.SQLException {var t=r.getTimestamp(name);return t==null?null:t.toInstant();}
 private IncidentView row(java.sql.ResultSet r,int n)throws java.sql.SQLException {return new IncidentView(r.getString("id"),r.getLong("environment_id"),"MANUAL",r.getString("service"),r.getString("title"),r.getString("description"),instant(r,"occurred_at"),instant(r,"created_at"),IncidentStatus.valueOf(r.getString("status")),instant(r,"resolved_at"));}
 private AnalysisJobView jobRow(java.sql.ResultSet r,int n)throws java.sql.SQLException {return new AnalysisJobView(r.getString("id"),r.getString("incident_id"),r.getLong("environment_id"),r.getString("status"),r.getString("reason"),instant(r,"created_at"),instant(r,"updated_at"));}
 private void environment(Long id,boolean lock){if(db.queryForList("SELECT id FROM environments WHERE id=?"+(lock?" FOR UPDATE":""),id).isEmpty())throw error(HttpStatus.NOT_FOUND,"ENVIRONMENT_NOT_FOUND");}
 private static org.springframework.web.server.ResponseStatusException error(HttpStatus status,String code){return PrometheusClient.error(status,code,code);}
 private IncidentView incident(Long env,UUID id,boolean lock){var rows=db.query("SELECT * FROM incidents WHERE environment_id=? AND id=?"+(lock?" FOR UPDATE":""),this::row,env,id.toString());if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"INCIDENT_NOT_FOUND");return rows.getFirst();}

 @Operation(summary="장애 의심 상황 수동 기록",description="자동 탐지 결과가 아닙니다. requestKey UUID로 재전송을 중복 방지합니다. 같은 키와 다른 내용은 409. 시각은 마이크로초 단위 저장. 서비스는 수동 입력이며 존재/장애 여부를 검증하지 않습니다.")
 @PostMapping @Transactional
 IncidentView create(@PathVariable Long environmentId,@Valid @RequestBody IncidentInput input){
  environment(environmentId,true);
  var prior=db.query("SELECT * FROM incidents WHERE environment_id=? AND request_key=?",this::row,environmentId,input.requestKey().toString());
  if(!prior.isEmpty()){var x=prior.getFirst();if(!x.service().equals(input.service())||!x.title().equals(input.title().strip())||!x.description().equals(input.description())||!x.occurredAt().equals(ts(input.occurredAt()).toInstant()))throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");return x;}
  UUID id=UUID.randomUUID();db.update("INSERT INTO incidents(id,environment_id,request_key,service,title,description,occurred_at,created_at,status) VALUES(?,?,?,?,?,?,?,?,?)",id.toString(),environmentId,input.requestKey().toString(),input.service(),input.title().strip(),input.description(),ts(input.occurredAt()),ts(Instant.now()),"OPEN");return incident(environmentId,id,false);
 }
 @Operation(summary="장애 기록 목록",description="발생 시각 내림차순. from 포함/to 제외, 서비스·OPEN/RESOLVED 선택 필터. page 0부터, size 최대 50. 수동 기록이며 모델의 장애 판정이 아닙니다.")
 @GetMapping
 IncidentPage list(@PathVariable Long environmentId,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size,@RequestParam(required=false) String service,@RequestParam(required=false) IncidentStatus status,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to){
  environment(environmentId,false);if(from!=null&&to!=null&&!from.isBefore(to))throw error(HttpStatus.BAD_REQUEST,"INVALID_QUERY");
  String where=" WHERE environment_id=?";var args=new ArrayList<Object>();args.add(environmentId);
  if(service!=null){if(!service.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))throw error(HttpStatus.BAD_REQUEST,"INVALID_QUERY");where+=" AND service=?";args.add(service);}
  if(status!=null){where+=" AND status=?";args.add(status.name());}if(from!=null){where+=" AND occurred_at>=?";args.add(ts(from));}if(to!=null){where+=" AND occurred_at<?";args.add(ts(to));}
  long count=db.queryForObject("SELECT COUNT(*) FROM incidents"+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
  return new IncidentPage(db.query("SELECT * FROM incidents"+where+" ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",this::row,args.toArray()),page,size,count,(count+size-1)/size);
 }
 @Operation(summary="장애 기록 상세",description="환경에 속한 기록만 반환합니다. 원인 서비스나 RCA 결과를 생성하지 않습니다.")
 @GetMapping("/{incidentId}") IncidentView detail(@PathVariable Long environmentId,@PathVariable UUID incidentId){return incident(environmentId,incidentId,false);}
 @Operation(summary="장애 기록 수동 종료 또는 재개",description="OPEN/RESOLVED로 변경합니다. 수동 종료는 자동 회복 판정이 아닙니다. 종료 시 대기 분석 작업은 취소됩니다. 재개해도 기존 작업을 실행하지 않습니다.")
 @PatchMapping("/{incidentId}") @Transactional
 IncidentView update(@PathVariable Long environmentId,@PathVariable UUID incidentId,@Valid @RequestBody IncidentUpdate input){
  var x=incident(environmentId,incidentId,true);if(x.status()==input.status())return x;
  var now=ts(Instant.now());db.update("UPDATE incidents SET status=?,resolved_at=? WHERE id=?",input.status().name(),input.status()==IncidentStatus.RESOLVED?now:null,incidentId.toString());
  if(input.status()==IncidentStatus.RESOLVED)db.update("UPDATE analysis_jobs SET status='CANCELLED',reason='INCIDENT_RESOLVED',updated_at=? WHERE incident_id=? AND status='BLOCKED_MODEL_UNAVAILABLE'",now,incidentId.toString());
  return incident(environmentId,incidentId,false);
 }
 @Operation(summary="RCA 분석 요청 기록",description="모델 미연결로 BLOCKED_MODEL_UNAVAILABLE 상태만 저장하며 실행하지 않습니다. 열린 장애마다 대기 작업 하나만 유지합니다. 다른 키로 대기 작업 중복 요청 시 409. 동일 requestKey 재전송은 취소 후에도 기존 작업 반환. 종료된 기록의 새 요청은 409.")
 @PostMapping("/{incidentId}/rca-jobs") @Transactional
 AnalysisJobView createJob(@PathVariable Long environmentId,@PathVariable UUID incidentId,@Valid @RequestBody JobInput input){
  var x=incident(environmentId,incidentId,true);
  var prior=db.query("SELECT j.*,i.environment_id FROM analysis_jobs j JOIN incidents i ON i.id=j.incident_id WHERE j.incident_id=? AND j.request_key=?",this::jobRow,incidentId.toString(),input.requestKey().toString());if(!prior.isEmpty())return prior.getFirst();
  if(x.status()==IncidentStatus.RESOLVED)throw error(HttpStatus.CONFLICT,"INCIDENT_RESOLVED");
  var active=db.query("SELECT j.*,i.environment_id FROM analysis_jobs j JOIN incidents i ON i.id=j.incident_id WHERE j.incident_id=? AND j.status='BLOCKED_MODEL_UNAVAILABLE'",this::jobRow,incidentId.toString());if(!active.isEmpty())throw error(HttpStatus.CONFLICT,"ACTIVE_ANALYSIS_JOB_EXISTS");
  UUID id=UUID.randomUUID();var now=ts(Instant.now());db.update("INSERT INTO analysis_jobs(id,incident_id,request_key,status,reason,created_at,updated_at) VALUES(?,?,?,'BLOCKED_MODEL_UNAVAILABLE','MODEL_UNAVAILABLE',?,?)",id.toString(),incidentId.toString(),input.requestKey().toString(),now,now);return job(environmentId,incidentId,id);
 }
 @Operation(summary="장애별 분석 요청 이력",description="최근 요청부터 페이지 조회합니다. 현재 상태는 모델 미연결 대기 또는 취소만 지원합니다.")
 @GetMapping("/{incidentId}/rca-jobs")
 AnalysisJobPage jobs(@PathVariable Long environmentId,@PathVariable UUID incidentId,@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size){incident(environmentId,incidentId,false);long count=db.queryForObject("SELECT COUNT(*) FROM analysis_jobs WHERE incident_id=?",Long.class,incidentId.toString());return new AnalysisJobPage(db.query("SELECT j.*,i.environment_id FROM analysis_jobs j JOIN incidents i ON i.id=j.incident_id WHERE j.incident_id=? ORDER BY j.created_at DESC,j.id DESC LIMIT ? OFFSET ?",this::jobRow,incidentId.toString(),size,(long)page*size),page,size,count,(count+size-1)/size);}
 @Operation(summary="분석 작업 상태 상세",description="환경·장애·작업 ID를 함께 검증합니다. 모델 미연결 상태에서 결과나 성공 시각은 제공하지 않습니다.")
 @GetMapping("/{incidentId}/rca-jobs/{jobId}")
 AnalysisJobView job(@PathVariable Long environmentId,@PathVariable UUID incidentId,@PathVariable UUID jobId){var rows=db.query("SELECT j.*,i.environment_id FROM analysis_jobs j JOIN incidents i ON i.id=j.incident_id WHERE i.environment_id=? AND i.id=? AND j.id=?",this::jobRow,environmentId,incidentId.toString(),jobId.toString());if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"ANALYSIS_JOB_NOT_FOUND");return rows.getFirst();}
 @Operation(summary="대기 분석 요청 취소",description="모델 미연결 대기 작업을 CANCELLED로 변경합니다. 반복 취소는 같은 결과를 반환하며 실행 완료로 바꾸지 않습니다.")
 @PostMapping("/{incidentId}/rca-jobs/{jobId}/cancel") @Transactional
 AnalysisJobView cancel(@PathVariable Long environmentId,@PathVariable UUID incidentId,@PathVariable UUID jobId){incident(environmentId,incidentId,true);job(environmentId,incidentId,jobId);db.update("UPDATE analysis_jobs SET status='CANCELLED',reason='USER_CANCELLED',updated_at=? WHERE id=? AND status='BLOCKED_MODEL_UNAVAILABLE'",ts(Instant.now()),jobId.toString());return job(environmentId,incidentId,jobId);}
}
