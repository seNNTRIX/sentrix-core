# SentriX 백엔드 개발 현황 및 문제 해결 기록

최종 갱신: 2026-10-28 · 기준 저장소: `seNNTRIX/sentrix-core`

이 문서는 **앞으로 할 일, 완료한 일, 개발 중 발견한 문제와 해결 근거**를 관리하는 기준 문서다. 기존 상세 개발 이력은 [DEVELOPMENT.md](DEVELOPMENT.md), 실행 방법과 현재 API 계약은 [README](../README.md)에 보존한다.

## 1. 현재 어디까지 됐나

- Java 21 / Spring Boot / MySQL 8.4 기반으로 환경·관측 저장소 설정을 저장한다.
- Prometheus 메트릭·Deployment 준비 상태, Loki 로그, Tempo 트레이스를 조회하는 **14개 API를 구현**했다.
- Swagger에서 구현된 14개 API를 한국어 설명과 함께 직접 호출할 수 있다(BE-16 완료).
- 대시보드의 기존 디자인에 실제 조회 결과를 연결했다. 원본 관측 데이터는 MySQL에 복제하지 않는다.
- 자동 이상 탐지, 장애 이력 저장, Python 모델 호출, RCA 결과 저장은 **아직 구현 전**이다. 현재 준비 상태 조회는 ML 장애 판정이 아니다.
- 아래 완료 표시는 현재 로컬 코드와 기존 검증 기록 기준이다. 커밋·푸시·배포 완료를 뜻하지 않는다. 테스트 실행 여부와 범위는 아래 날짜별 작업 기록을 기준으로 확인한다.

## 2. 기록 방법

| 표시 | 뜻 | 갱신 기준 |
|---|---|---|
| `[ ] 대기` | 개발 전 | 범위와 완료 조건을 읽고 시작 |
| `[ ] 진행` | 구현 또는 검증 중 | 구현했어도 필요한 검증이 남으면 유지 |
| `[ ] 보류` | 결정·외부 결과를 기다림 | 기다리는 항목과 관련 문제 ID 기재 |
| `[x] 완료` | 해당 행의 범위가 구현·검증됨 | 검증 근거와 작업 기록 추가 |
| `[ ] 후속` | 이번 핵심 시연 이후 범위 | 필요할 때 범위를 확정하고 대기로 변경 |

개발할 때 다음을 같이 갱신한다.

1. 아래 작업 ID의 상태와 근거를 수정한다. 새 작업은 새 ID로 추가하고 기존 ID는 재사용하지 않는다.
2. 문제를 발견하면 `ISS-번호`를 추가한다. **증상·확인한 원인·추정·조치·검증·남은 일**을 구분한다.
3. 작업 기록에 날짜, 관련 ID, 변경 내용, 테스트 결과, 남은 일을 남긴다.
4. API 계약이 바뀌면 README도 수정한다. 중요한 설계 결정은 `docs/decisions/`에 기록하고 연결한다.
5. 해결되지 않은 문제, 실행하지 않은 테스트, 미연결 모델을 완료로 표시하지 않는다. 비밀번호·토큰·개인정보가 담긴 로그는 기록하지 않는다.
6. 사용자 요청에 따라 의미 있는 변경 단위마다 로컬 커밋하고, 개발·검증·기록 갱신을 마친 뒤 작업 종료 시 한 번에 푸시한다. PR은 만들지 않는다. 원격 브랜치와 HEAD가 일치하는지 확인하고 결과를 보고한다. 과거 기록의 “커밋·푸시 없음”은 당시의 상태다.

## 3. API 작업 목록

`{id}`는 관측 환경 ID, `{service}`는 서비스 이름이다. `{type}`은 `prometheus`, `loki`, `tempo`다. **미구현 API 경로는 설계안이며 계약 확정 시 변경 가능하다.** 구현된 행은 현재 범위만 완료로 판단한다.

### 3.1 구현된 API

| ID | 상태 | Method · 경로 | 하는 일 / 완료 범위 | 근거 |
|---|---|---|---|---|
| API-01 | [x] 완료 | `GET /actuator/health` | 서버·DB 연결 상태 확인 | Actuator 설정, 환경 API 테스트, 실제 UP 확인 기록 |
| API-02 | [x] 완료 | `POST /api/v1/environments` | 환경 이름·namespace 등록 | EnvironmentController, EnvironmentApiTests |
| API-03 | [x] 완료 | `GET /api/v1/environments` | 환경 목록 페이지 조회 | EnvironmentController, EnvironmentApiTests |
| API-04 | [x] 완료 | `GET /api/v1/environments/{id}` | 환경 상세 조회 | EnvironmentController, EnvironmentApiTests |
| API-05 | [x] 완료 | `PUT /api/v1/environments/{id}/data-sources/{type}` | 관측 저장소 접속 설정 등록·수정 | DataSourceController, DataSourceApiTests |
| API-06 | [x] 완료 | `GET /api/v1/environments/{id}/data-sources` | 설정·마지막 연결 테스트 결과 조회 | DataSourceController, DataSourceApiTests |
| API-07 | [x] 완료 | `POST /api/v1/environments/{id}/data-sources/{type}/test` | 저장소 readiness 검사·결과 저장 | DataSourceController, DataSourceApiTests |
| API-08 | [x] 완료 | `GET /api/v1/environments/{id}/services` | 현재 namespace의 Deployment 목록 조회 | TelemetryController, TelemetryApiTests |
| API-09 | [x] 완료 | `GET /api/v1/environments/{id}/services/{service}/status` | 목표·현재·준비·가용 Pod 수와 수집 시각 조회 | DeploymentStatusController, TelemetryApiTests |
| API-10 | [x] 완료 | `GET /api/v1/environments/{id}/metrics` | CPU·메모리·요청량·오류율·p50/p90/p99 시계열 조회 | TelemetryController, TelemetryApiTests, PromqlTests |
| API-11 | [x] 완료 | `GET /api/v1/environments/{id}/logs` | 서비스·기간·문자열별 로그 검색 | LogController, LogApiTests |
| API-12 | [x] 완료 | `GET /api/v1/environments/{id}/traces` | 서비스·기간별 요청 추적 검색 | TraceController, TraceApiTests |
| API-13 | [x] 완료 | `GET /api/v1/environments/{id}/traces/{traceId}` | 요청의 처리 구간·부모 관계·소요 시간 조회 | TraceController, TraceApiTests |

### 3.2 관측·장애 분석에 필요한 API

| ID | 상태 | Method · 경로(설계안) | 하는 일 / 완료 조건 | 선행 작업 |
|---|---|---|---|---|
| API-14 | [x] 완료 | `GET /api/v1/environments/{id}/collection-status?service=frontend` | 서비스별 대표 지표·로그·trace 최신성, 미관측·미설정·부분 응답·실패 구분 | BE-06, CollectionStatusApiTests |
| API-15 | [ ] 진행 | `GET /api/v1/environments/{id}/service-map` | 관측된 서비스 간 호출 관계와 연결별 지표 반환 | BE-07 |
| API-16 | [ ] 대기 | `GET /api/v1/environments/{id}/services/{service}/pods` | 서비스 소속 Pod·준비 상태·재시작 횟수 반환 | 소유 관계 및 수집 지표 확인 |
| API-17 | [ ] 대기 | `GET /api/v1/environments/{id}/services/{service}/pods/{pod}/metrics` | 해당 서비스에 속한 Pod의 CPU·메모리 시계열 반환 | API-16, 소속 검증 |
| API-18 | [ ] 대기 | `GET /api/v1/environments/{id}/logs/volume` | 전체 대상 로그의 시간별 건수 집계 반환 | Loki 집계 쿼리·분류 기준 확정 |
| API-19 | [ ] 대기 | `GET /api/v1/environments/{id}/overview` | 화면 주요 지표·관측 상태·진행 중 장애 수 요약 반환 | 집계 시각·부분 실패 계약 확정 |
| API-20 | [ ] 진행 | `GET /api/v1/environments/{id}/monitoring` | 분석 실행 상태·마지막 성공 시각·실패 이유 반환 | BE-09, BE-13 |
| API-21 | [ ] 진행 | `PUT /api/v1/environments/{id}/monitoring` | 자동 분석 활성화·중지와 사용할 모델 설정 저장 | BE-08, BE-09 |
| API-22 | [ ] 보류 | `GET /api/v1/environments/{id}/anomaly-scores` | 실제 분석 점수·임계값·판정·모델 버전의 시계열 반환 | BE-10~13, 모델 연동 |
| API-23 | [ ] 대기 | `GET /api/v1/environments/{id}/service-status/history` | 서비스별 정상·경고·이상·미판정 구간 반환 | BE-12, BE-13, ISS-05 |
| API-24 | [ ] 대기 | `GET /api/v1/environments/{id}/incidents` | 기간·서비스·상태별 장애 목록 페이지 조회 | BE-13 |
| API-25 | [ ] 대기 | `GET /api/v1/environments/{id}/incidents/{incidentId}` | 장애 시각·영향 서비스·분석 진행 상태 조회 | BE-13 |
| API-26 | [ ] 대기 | `POST /api/v1/environments/{id}/incidents/{incidentId}/acknowledgements` | 사용자 확인 기록 저장; 자동 회복 판정과 구분 | BE-13, 사용자 식별 방식 |
| API-27 | [ ] 보류 | `GET /api/v1/environments/{id}/incidents/{incidentId}/rca` | 원인 서비스·연결 순위, 근거, 중간·최종 결과 반환 | BE-11~13, 모델 연동 |
| API-28 | [ ] 대기 | `POST /api/v1/environments/{id}/incidents/{incidentId}/rca-jobs` | 분석·재분석 작업 접수; 중복 실행과 미연결 모델 처리 | BE-08, BE-11, BE-13 |
| API-29 | [ ] 대기 | `GET /api/v1/environments/{id}/incidents/{incidentId}/rca-jobs/{jobId}` | 대기·실행·완료·실패와 관련 결과 조회 | BE-13 |
| API-30 | [ ] 대기 | `GET /api/v1/environments/{id}/events/stream` | 장애·점수·RCA 결과 갱신을 SSE로 전달 | BE-15; 초기에는 주기 조회로 대체 가능 |

목록·저장 구조·실패 처리는 모델 완성 전 개발할 수 있다. 시험용 모델 응답은 테스트 안에서만 사용하고 실제 화면의 판정으로 노출하지 않는다.

### 3.3 후속 서비스 기능

| ID | 상태 | Method · 경로(설계안) | 하는 일 | 범위 판단 |
|---|---|---|---|---|
| API-31 | [ ] 후속 | `POST /api/v1/auth/login` | 로그인 | 외부 사용자 공개 전 필요 |
| API-32 | [ ] 후속 | `POST /api/v1/auth/logout` | 로그인 세션 종료 | 인증 방식 확정 후 구현 |
| API-33 | [ ] 후속 | `GET /api/v1/users/me` | 현재 사용자 조회 | 환경 접근 권한 구현과 연결 |
| API-34 | [ ] 후속 | `GET /api/v1/models` | 모델 버전·feature 계약·사용 가능 상태 조회 | 모델 관리 화면 연동 시 구현 |
| API-35 | [ ] 후속 | `GET /api/v1/models/{modelId}/evaluations` | 평가 데이터셋·지표·평가 조건 조회 | 팀원이 만든 평가 결과를 등록·조회; 온라인 자동 평가와 구분 |
| API-36 | [ ] 후속 | `GET /api/v1/notifications` | 사용자 알림 목록 조회 | 장애 알림 범위 확정 후 구현 |
| API-37 | [ ] 후속 | `PATCH /api/v1/notifications/{notificationId}` | 알림 읽음 상태 변경 | API-36 |

회원가입·비밀번호 재설정·팀 초대·알림 규칙·감사 이력·환경 삭제는 필요 시 세부 계약을 정한다. 화면에 존재한다는 이유만으로 이번 졸작 시연의 필수 범위로 확정하지 않는다. 부하 생성·장애 주입은 우선 별도 데모 도구로 실행하며, 제품이 계속 관측하는 기능과 분리한다.

## 4. API 외 백엔드 작업 목록

| ID | 상태 | 작업 | 완료 조건 / 검증 기준 | 관련 항목 |
|---|---|---|---|---|
| BE-01 | [x] 완료 | Java 서버·MySQL·Flyway 기반 구성 | 환경·데이터 소스 테이블 생성, JPA 검증, 로컬 서버·DB 연결 | V1/V2 migration, API-01~07 |
| BE-02 | [x] 완료 | 관측 저장소 HTTP 조회 공통 처리 | 허용 URL 확인, 리다이렉트 금지, 본문 포함 timeout, 응답 4MiB 제한 | DataSourceHttpClient, 기존 API 테스트 |
| BE-03 | [x] 완료 | 조회 입력 검증과 데이터 경계 처리 | 환경·서비스 조건 제한, 시간/개수 제한, null과 0 구분, 실패를 빈 성공으로 숨기지 않음 | 현재 API 범위; 사용자별 권한은 별도 |
| BE-04 | [x] 완료 | 자동 테스트·CI 구성 | API 테스트와 PromQL 검사, MySQL 테스트 CI 정의 | 기존 30개 테스트 기록; 원격 CI 최신 성공 여부는 별도 |
| BE-05 | [x] 완료 | 로컬 관측 환경·대시보드 연동 | 실제 메트릭·로그·트레이스 조회를 기존 화면에서 확인 | dev/observability, DEVELOPMENT.md; 짧은 기능 검증 범위 |
| BE-06 | [x] 완료 | 수집 데이터 최신성 판정(1차) | 최근 30분의 대표 신호 시각과 180초 기준 최신성 반환; 무트래픽·미계측·수집 중단의 원인은 미확정으로 명시 | API-14, ISS-04; 전체 수집 품질은 후속 |
| BE-07 | [ ] 진행 | 서비스 식별·호출 관계 구성 | Deployment와 trace service 이름 매핑, 관측 도구 구분, 미수집 관계 처리 | API-15, ISS-06 |
| BE-08 | [ ] 대기 | Java ↔ Python 분석 계약 확정 | 요청·응답 스키마, 작업 ID, 모델/feature 버전, 시각·단위·누락값·실패 코드 합의 | 팀원과 합의 필요; 내부 경로도 이때 확정 |
| BE-09 | [ ] 대기 | 주기 분석 실행부 | 환경별 시작·중지, 중복 실행 방지, 처리 지연 시 밀린 작업 정책, 최근 실행 상태 기록 | API-20/21; 화면 요청과 독립 실행 |
| BE-10 | [ ] 대기 | 분석용 시간 구간 데이터 준비 | 수집 지연을 고려한 구간 정렬, 중복·누락 처리, 학습과 동일한 feature 입력 보장 | Python과 역할 분담; 화면 검색 상한 재사용 금지 |
| BE-11 | [ ] 보류 | Python 모델 호출·응답 검증 | 버전 확인, timeout·실패·재시도·취소 처리, 응답 작업 ID/환경 검증 | 계약 먼저 개발 가능; 실제 검증은 모델 필요 |
| BE-12 | [ ] 대기 | 이상·회복·RCA 진행 상태 관리 | 연속 조건·baseline·시작/종료 기준, 중간/최종 순위 구분, 결측 시 미판정 | 모델팀 기준 확인; 알고리즘을 Java에서 중복 구현하지 않음 |
| BE-13 | [ ] 대기 | 장애·분석·결과 MySQL 저장 | 작업·점수·상태 구간·순위·근거·모델 버전 migration, 트랜잭션, 중복 저장 방지, 조회 인덱스 | API-20~29 |
| BE-14 | [ ] 대기 | 재시작·실패 복구 | 실행 중 서버 종료, 모델 지연/중단, 저장 실패, 재시도에도 중복 장애·순위 생성 방지 | BE-09~13 |
| BE-15 | [ ] 대기 | 화면 갱신과 이벤트 전달 | 초기 조회, SSE 재접속·누락 후 재조회, 연결 정리, 환경 전환 격리 | API-30; 초기 polling 가능 |
| BE-16 | [x] 완료 | Swagger / OpenAPI 문서 | 구현 API의 입력·응답·오류·호출 예시를 실제 서버에서 확인 | springdoc 3.2.0, OpenApiTests, 실제 Swagger 호출; ISS-09 |
| BE-17 | [ ] 대기 | 백엔드 자체 운영 지표·로그 | 요청/분석 작업 ID로 추적, 저장소·모델 지연과 실패율, 분석 지연 모니터링 | 기존 Actuator health만으로 완료 아님 |
| BE-18 | [ ] 대기 | 로컬 실행 절차 정리·재현 | Java/MySQL/관측 저장소/프론트 실행·중지·포트 연결, 깨끗한 환경에서 재현 | 현재 수동 절차 있음; 재현 검증 필요 |
| BE-19 | [ ] 대기 | 통합 시연·실패 시나리오 검증 | 정상 관측 → 장애 탐지 → RCA → 회복, 저장소/모델 단절·재시작 포함 | 실제 모델 필요; 탐지와 원인 순위 성능은 별도 평가 |
| BE-20 | [ ] 대기 | 성능·리소스 검증 | 동시 조회·반복 분석·장시간 실행 시 지연/메모리/실패율 측정 후 한도 결정 | ISS-01/02; 짧은 성공으로 안정성 완료 처리 금지 |
| BE-21 | [ ] 후속 | 사용자 인증·환경 접근 권한 | 타 사용자 환경·로그·trace·SSE 접근 차단, 비밀 설정 보호 | API-31~33; 외부 공개 전 필요 |
| BE-22 | [ ] 후속 | 데이터 보존·운영 배포 | 결과 보존 기간·DB 백업/복원·비밀 관리·배포 절차 검증 | 로컬 졸작 시연과 운영 서비스 범위 구분 |

## 5. 권장 개발 순서와 다음 작업

| 순서 | 작업 묶음 | 이 단계에서 보여줄 결과 |
|---|---|---|
| 1 | BE-16, BE-06 + API-14 | Swagger에서 현재 API 확인, 실제 수집 상태 표시 |
| 2 | BE-07 + API-15 | 실제 호출 관계를 서비스 맵에 표시 |
| 3 | BE-08, BE-13 + API-24/25/29 | 모델 계약 문서, 장애·작업 테이블과 조회; 실데이터 없으면 빈 결과 |
| 4 | BE-09/10/12/14 + API-20/21 | 자동 분석 실행·중지·복구 구조; 모델 없으면 미연결 상태 |
| 5 | BE-11 + API-22/23/27/28 | 실제 모델로 점수·상태 이력·원인 순위 저장 및 조회 |
| 6 | BE-15/19/20 + API-30 | 화면 갱신, 전체 시연, 실패 복구와 성능 검증 |

Pod별 지표, 로그 집계, 개요 집계 API는 관측 화면 요구에 맞춰 2~4단계 사이에 진행한다. **Swagger(BE-16)와 수집 데이터 최신성(BE-06/API-14) 1차 구현을 완료했다. 다음은 BE-07/API-15 서비스 식별·호출 관계 조회다.** 이 문서의 대기 항목은 아직 개발 시작 전이다.

## 6. 개발 중 문제와 해결 현황

아래는 기존 코드·개발 기록에서 확인한 항목이다. 해결 시도와 해결 확정을 구분한다.

| ID | 상태 | 관련 작업 | 증상 / 확인 사실 | 원인 또는 아직 모르는 점 | 조치 / 검증 | 남은 일 |
|---|---|---|---|---|---|---|
| ISS-01 | 관찰 중 | BE-05/20, API-12/13 | 로컬 Tempo 검색에 5~10초 시간 초과 발생 | 제한된 로컬 자원 환경; 단일 원인 확정 안 됨 | Tempo CPU 상한 1코어·소스 timeout 10초 조정 후 실제 trace 상세·화면 확인 | 장시간·동시 조회 측정; 근본 해결로 표시하지 않음 |
| ISS-02 | 관찰 중 | BE-20, API-10 | 개요 화면 지연 지표에서 Prometheus timeout 1회 관측 | 정확한 원인 미확인 | 동일 종류 API와 화면 재조회에서 실제 지표 확인; collection-status 실제 재조회에서도 timeout 관측, 다른 신호는 유지 | 재현 조건·동시 요청·저장소 지연 측정 |
| ISS-03 | 해결 | BE-03, API-13 | Tempo의 ID 표현과 불완전 부모 관계를 그대로 쓰기 어려움 | 0으로 시작하는 ID 축약, base64/hex 표현, 외부 부모 span 미수집 가능 | ID 정규화, 부모 누락 경고, 중복/순환 검증; TraceApiTests와 실제 상세 조회 | 새 저장소 버전 적용 시 계약 재확인 |
| ISS-04 | 부분 해결 | BE-06, API-14 | 연결 성공과 최신 데이터 존재는 다름 | 대표 시각만으로 무트래픽·미계측·중단의 원인은 확정 불가 | 신호별 최신성·미설정·부분 검색·실패 구분 구현, 테스트 및 실제 저장소 조회 확인 | 전체 Pod 계측 범위·수집기 heartbeat·수신 지연에 기반한 품질 판정은 후속 |
| ISS-05 | 미해결 | BE-12/13, API-23 | 서비스 타임라인에 정상·경고·이상 이력이 없음 | 준비 상태 API는 현재 replica 상태만 조회하며 장애 이력 저장/판정 미구현 | 타임라인 구조 복원, 회색은 미판정이라고 명시 | 환경 단위 이상 점수를 서비스별 상태로 어떻게 연결할지 먼저 결정 |
| ISS-06 | 미해결 | BE-07, API-08/15 | 서비스 목록에 수집기·Prometheus·loadgenerator도 포함됨 | 현재 인벤토리가 namespace 내 모든 Deployment 기준 | 실제 목록임을 문서와 화면에서 명시; 임의 서비스 목록 하드코딩 안 함 | 업무 서비스 식별 기준·워크로드 종류·trace 이름 매핑 결정 |
| ISS-07 | 미해결 | BE-08/10, API-11/12 | 화면 로그·trace 검색을 ML 입력에 재사용하면 데이터 일부가 빠짐 | 로그 최대 500건, trace 먼저 검색된 N건 및 응답 상한 | 화면 조회용이라는 경계 문서화 | 분석 전용 집계/조회 및 feature 계약 확정 |
| ISS-08 | 해결 | BE-05 | API 연결 과정에서 기존 대시보드 디자인이 다른 카드로 대체됨 | 실데이터 UI를 원래 컴포넌트와 별도로 구성 | 원래 화면에 연결하도록 수정, 개요 타임라인/막대/시간축 복원; UI 테스트·실제 화면 확인 | 이후에도 디자인 변경 없이 데이터 연결; 화면 변경은 요청 범위 확인 |
| ISS-09 | 해결 | BE-16 | 실제 OpenAPI JSON에서 오류 응답의 ApiProblem 참조 대상 스키마 누락 | 초기 Components에 등록한 사용자 스키마가 최종 문서에 남지 않은 것을 확인 | 문서 생성 후 응답 참조와 함께 스키마 등록하도록 변경; 스키마 존재 검사 추가 | 실제 문서의 모든 참조 해석 확인, OpenApiTests 2개 통과; 새 응답 추가 시 검사 유지 |
| ISS-10 | 관찰 중 | BE-06, API-14 | 실제 Tempo 최신 검색에서 completedJobs가 totalJobs보다 작은 응답 관측 | 최신 검색의 조기 종료인지 누락인지 현재 응답만으로 확정하지 않음 | 시각은 제공하되 PARTIAL/UNKNOWN, UPSTREAM_PARTIAL로 보수적으로 표시; 부분 검색 테스트 통과 | Tempo 검색 완료 의미·부하 조건 확인 후 판정 개선 |

새 문제의 상세 기록 양식:

```md
### ISS-12 — 문제 제목
- 발견일 / 해결일:
- 상태: 조사 중 / 조치 중 / 관찰 중 / 해결 / 보류
- 관련 작업 ID:
- 환경·버전·재현 조건:
- 증상 / 기대 동작:
- 확인한 원인:
- 아직 확인하지 못한 가설:
- 조치와 선택 이유:
- 검증 방법 / 실제 결과:
- 관련 파일·커밋·로그 위치:
- 남은 위험 / 다음 확인:
```

## 7. 작업 기록

### 2026-10-28 — 현황표 도입

- 관련: API-01~37, BE-01~22, ISS-01~08.
- 코드의 Controller 매핑과 README, DEVELOPMENT 기록을 대조해 구현 API 13개를 체크했다.
- 비API 작업과 미구현 API는 별도 행으로 나눴고, 모델이 필요한 부분과 모델 없이 준비할 수 있는 부분을 구분했다.
- 기존 기록에는 백엔드 테스트 30개, 실제 Prometheus 쿼리 사례 12개, 실제 메트릭·로그·trace 대시보드 검증이 있다. 이 문서 작성 시 애플리케이션 테스트는 재실행하지 않았다.
- 기존 기록을 삭제하지 않고 이 문서를 앞으로의 상태 기준으로 연결했다. 프로그램 동작 변경 없음. 이 작업의 커밋·푸시는 하지 않았다.
- 문서 검증: API 37개(구현 13개), 내부 작업 22개, 문제 기록 8개의 ID 중복·누락과 상대 링크를 검사했고 `git diff --check`를 통과했다.
- 다음 후보: BE-16(Swagger), BE-06/API-14(수집 상태).

### 2026-10-28 — Swagger / OpenAPI 연결

- 관련: BE-16, ISS-09. 상태: 대기 → 진행 → 완료.
- `springdoc-openapi-starter-webmvc-ui:3.2.0`을 추가하고 실제 Controller에서 문서를 생성한다. 기존 13개 API만 포함하며 예정 API는 노출하지 않는다.
- 한국어 기능 설명, 데이터 소스/메트릭 선택 목록, 조회 제한, 성공·오류 응답과 schema를 문서화했다. 환경 생성의 201 및 연결 검사 실패의 200+FAILED 계약을 구분했다.
- 문서 요청 서버와 같은 주소로 실행하며 외부 validator를 비활성화했다. 비즈니스 처리, DB migration, 대시보드 디자인 변경 없음.
- 발견 문제: 초기 공통 오류 schema가 최종 문서에서 빠짐(ISS-09). 응답 참조와 schema를 생성 후 함께 등록하도록 바꾸고 테스트에 존재 검사를 추가했다.
- 검증: Java 21 `./mvnw -B verify`에서 H2 기반 전체 32개 테스트·빌드 통과. 문서 schema 수정 후 `./mvnw -B -Dtest=OpenApiTests verify`에서 해당 2개 재검증·빌드 통과.
- 실제 MySQL 개발 서버를 재시작해 health UP, OpenAPI 13개 operation, 모든 schema 참조의 해석 성공을 확인했다. Swagger의 Try it out → Execute로 health 200/UP과 실제 환경 목록 200을 확인했다.
- 확인 주소: `http://127.0.0.1:8080/swagger-ui/index.html`, JSON: `/v3/api-docs`. README에 호출 순서를 추가했다.
- 미실행: MySQL 전용 테스트 스위트·장시간 부하 테스트·원격 CI 재실행. 실제 DB 연결 및 조회 확인과 별개다.
- 관련 파일: `pom.xml`, `OpenApiConfig.java`, 각 Controller 문서 annotation, `application.properties`, `OpenApiTests.java`, README.
- 커밋·푸시 없음. 다음: BE-06/API-14 수집 상태 판정·조회.

### 2026-10-28 — 수집 데이터 최신성 API

- 관련: API-14, BE-06, ISS-04/10. API와 최신성 1차 판정은 완료; 전체 수집 품질·원인 판정은 후속이다.
- `GET /api/v1/environments/{id}/collection-status?service=frontend` 구현. 최근 30분의 Deployment/요청 지표, 로그, trace를 조회해 원본 시각·나이·최신성·조회 상태를 반환한다. 대표 지표의 0도 수집 증거로 인정한다.
- 현재 샘플과 timestamp 이력을 함께 조회해 평가 시각을 데이터 시각으로 오인하지 않는다. 로그는 최신 이벤트 1건, trace는 최신 검색 힌트를 사용한다. 무트래픽·미계측·수집 중단은 이 응답만으로 구별하지 않고 CAUSE_UNDETERMINED로 명시한다.
- 저장소별 실패를 격리하고 미설정·미관측·부분 검색·미래 시각을 구별했다. DB migration·설정 변경·연결 검사 기록 갱신 없음. 화면 변경 없이 Swagger에 연결했다.
- 초기 컴파일에서 Loki 조건문의 닫는 괄호 누락을 수정한 뒤 전체 검증을 다시 실행했다.
- 검증: Java 21 `./mvnw -B verify`의 H2 기반 전체 39개 테스트 및 빌드 통과. Prometheus v3.5.0 promtool로 기존 12개 + 최신성 2개 쿼리 사례 통과. CI에도 새 fixture 검증을 추가했다.
- 실제 MySQL 개발 서버 재시작 후 health UP. 최초 frontend 조회에서 메트릭 2종·로그 RECENT 및 trace PARTIAL/UNKNOWN 확인. Swagger Try it out → environmentId=1, service=frontend → Execute에서 HTTP 200과 저장소별 결과 확인.
- 실제 재조회 중 Prometheus/Loki timeout이 관측됐다(ISS-02). 최종 Swagger 응답은 Prometheus ERROR/UNKNOWN, 로그 RECENT, trace PARTIAL/UNKNOWN으로 실패를 숨기지 않고 다른 결과를 보존했다. 장시간 안정성 완료로 판단하지 않는다.
- trace 최신 검색에서 완료/전체 job 수 차이를 확인해 ISS-10에 기록했다. 조기 종료와 실제 누락의 원인은 미확정이며 보수적으로 UNKNOWN을 반환한다.
- 미실행: MySQL 전용 테스트 스위트, 장시간·동시 부하 시험, 원격 CI 결과 확인. Python 모델 미연결. 이 API는 수집기 heartbeat·전체 계측 품질을 대신하지 않는다.
- 사용자 확인: `http://127.0.0.1:8080/swagger-ui/index.html` → 02. 데이터 소스 → 서비스별 수집 데이터 최신성 확인. 서버 실행 유지.
- 관련 파일: CollectionStatusController/CollectionStatusApiTests, OpenApiConfig/OpenApiTests, CI, README, 현황표. 사용자 지침에 따라 이 변경 묶음을 현재 core 브랜치에 커밋·푸시한다. 실제 성공 여부와 해시는 최종 응답에서 보고한다. PR 없음.
- 다음: BE-07/API-15 서비스 식별·호출 관계 조회.

다음 작업부터 아래 형식을 복사해 날짜순으로 추가한다.

```md
### YYYY-MM-DD — 작업 제목
- 관련 작업 / 문제 ID:
- 작업 전 → 후 상태:
- 구현·변경 내용과 이유:
- 바뀐 API·DB·설정:
- 검증: 실행한 명령/방법, 환경, 실제 결과:
- 실행하지 못한 검증 / 남은 문제:
- 사용자 확인 방법: Swagger 또는 실제 연결된 대시보드 경로:
- 관련 파일 / 커밋·푸시 여부:
- 다음 작업:
```

## 8. 완료 체크 기준

- [ ] 요청한 기능과 필요한 오류·빈 데이터 처리가 구현됐다.
- [ ] 변경 위험에 맞는 검증을 실행하고 결과를 기록했다.
- [ ] API만 개발했다면 Swagger에서, 화면까지 연결했다면 실행 중인 프론트·백엔드에서 확인했다.
- [ ] DB 변경은 migration으로 남기고 개발 DB와 테스트 DB를 구분했다. 해당하지 않으면 작업 기록에 해당 없음으로 적는다.
- [ ] 관련 작업의 상태·근거, 문제 기록, README/API 계약을 갱신했다.
- [ ] 미연결 모델·미판정 상태·미실행 검증·남은 한계를 실제로 완료된 것처럼 표시하지 않았다.

이 체크리스트는 작업마다 적용하는 기준이다. 문서 전체의 완료 표시로 사용하지 않는다.

### 2026-10-28 — 요청 단위 서비스 맵 1차 구현 (검증 일부 남음)

- API-15/BE-07 진행 중. GET service-map에 필수 traceId를 받아 CLIENT → SERVER 직접 연결과 서버 처리 시간/명시적 ERROR/UNSET 수를 반환한다. 전체 트래픽 집계·Deployment 매핑·업무 서비스 분류는 미구현이다.
- Java 21 전체 42개 테스트 및 빌드 통과. Swagger에서 새 경로와 설명 표시 확인. MySQL 기반 서버 실행 유지. 실제 Tempo 성공 응답과 프론트 연결은 미검증이다.
- ISS-11: 클러스터 재기동 실패. 컨테이너는 Up이지만 k3s가 `failed to find interface with specified node ip`로 종료되어 API 서버 연결 불가. MySQL은 healthy. 기존 볼륨·데이터는 삭제하지 않았다. 정확한 IP 갱신 경로와 k3d 복구는 다음 작업이다. API를 완료로 표시하지 않는다.
- 구현 22c9d59, 테스트 1668d24를 별도 로컬 커밋. 문서 커밋 후 작업 커밋을 한 번에 푸시하고 원격 일치를 확인한다. 원격 CI·장시간 부하 검증 미실행.
- 다음: 기존 클러스터 IP 복구 → 실제 trace 성공 검증 → 기간 단위 호출 관계 수집/집계 설계.

### 2026-10-28 — ML 제외 전체 개발 요청 / 관측 환경 복구와 분석 설정

- 전체 비ML 작업을 계속 개발한다. 현재 전체 완료가 아니다. 인증/환경 접근 권한, Pod·로그 집계·개요, 장애/작업 저장, 이벤트, 운영 검증이 남아 있다. ML 학습·추론은 제외하며 계약 합의도 미완료다.
- ISS-11 조치: `/private/tmp/sentrix-tools/k3d cluster stop/start sentrix-local`로 도구 노드와 서버 노드를 순서대로 시작해 IP 일치를 복구. 기존 볼륨 보존. Prometheus/Loki/Tempo/frontend port-forward 재기동. Tempo 실제 trace와 service-map NO_OBSERVED_EDGES 응답 확인. 전체 연결이 있는 요청은 추가 검증 필요.
- API-20/21은 설정 저장·조회 1차 구현. V3 monitoring_settings, 환경 행 잠금으로 동시 최초 저장 직렬화. enabled=true도 BLOCKED_MODEL_UNAVAILABLE. 스케줄러·작업 저장·재시도는 미구현이므로 API와 BE-09를 완료로 표시하지 않는다.
- Java 21 H2 전체 44개 테스트·빌드 통과. 구현 8ac0236, 테스트 2d49954 별도 커밋. 실제 MySQL 서버에 V3 적용 확인은 아래 후속 검증 기록 참고.
- 전체 비ML 완료로 주장하지 않는다. MySQL 전용 전체 테스트·부하 검증·원격 CI 미실행. 현황표의 대기 항목은 여전히 남은 작업이다.

- 후속 검증: 실제 MySQL 서버 기동 및 GET monitoring의 DISABLED/UNAVAILABLE/null 응답 확인. OpenAPI의 GET/PUT monitoring 등록 확인. 브라우저 제어 연결 오류로 새 Swagger 항목의 화면 조작 검증은 미완료.

### 2026-10-28 — Swagger 브라우저 연결 복구 및 실호출 검증

- 이전 `browser.user is unavailable`는 애플리케이션 HTTP 오류가 아니라 브라우저 제어 연결 오류였다. 제어 세션을 초기화하고 현재 브라우저/탭 목록에서 Swagger 탭을 다시 연결했다. 현재 조작 복구를 확인했으며 도구 내부 재발 방지 수정은 범위 밖이다.
- backend health UP, Swagger HTML 200, GET monitoring 200 확인. Swagger 화면에서 06. 분석 실행 설정 → GET → Try it out → environmentId=1 → Execute로 실제 200 및 DISABLED/UNAVAILABLE 응답을 확인했다. 이전 화면 조작 미검증 항목을 해소했다.
- 애플리케이션 코드·DB 설정 변경 없음. 전체 테스트는 재실행하지 않았다. 전체 비ML 개발 및 관측 저장소의 장시간 지연 검증이 완료됐다는 의미는 아니다.
