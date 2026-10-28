# 백엔드 개발 기록

## 2026-10-27 — Java 백엔드 기반

백엔드 개발 기준 저장소를 `seNNTRIX/sentrix-core`로 정하고 Java 21, Spring Boot, MySQL 8.4를 채택했다. Python은 feature/model-server 영역에서 사용한다.

첫 기능은 관측 환경 등록·목록·상세 조회다. 환경은 `Online Boutique` 같은 이름과 `sentrix` 같은 Kubernetes namespace를 가진다. 연결 상태나 서비스 건강 상태는 데이터 수신 근거가 생긴 뒤 추가한다.

| 영역 | 구현 상태 |
| --- | --- |
| Java 서버·MySQL 설정 | 구현 |
| Flyway 마이그레이션·JPA 검증 | 구현 |
| 환경 등록·조회·입력 검증 | 구현 |
| Health API | 구현 |
| Prometheus·Loki·Tempo 조회 | 예정 |
| 모델 호출·진단 저장 | 예정 |
| 사용자 인증·dashboard 연결 | 예정 |

테스트는 등록→상세→목록, 페이지 처리, 중복 등록, 잘못된 namespace/입력, 없는 환경, 상태 확인을 검증한다. H2 기본 테스트와 실제 MySQL 테스트는 구분한다.

검증 결과(2026-10-27):

- H2: API 테스트 2개 통과.
- Docker MySQL 8.4.13의 별도 `sentrix_test` DB: 같은 테스트 2개 통과, `verify` 및 실행 JAR 생성 성공.
- 기본 개발 DB `sentrix`로 서버 실행: health `UP`, 환경 등록 `201`, 상세·목록 `200`을 실제 HTTP 요청으로 확인.
- 개발 DB에 `Online Boutique` / namespace `sentrix` 환경 1건(id=1)을 등록했다. 이는 메타데이터이며 실제 수집 연결 상태를 뜻하지 않는다.
- GitHub Actions 결과는 PR의 체크에서 별도로 확인한다.

다음 작업: 관리자가 설정한 개발용 관측 저장소에 연결해 환경별 실제 메트릭을 조회한다. 팀원의 학습 데이터 파일 수집 작업과 독립적으로 진행한다. 0과 데이터 미수신은 응답에서 구분한다.

## 2026-10-27 — 데이터 소스 설정·연결 테스트

환경별 Prometheus·Tempo·Loki 접속 설정을 MySQL에 저장하고 실제 readiness HTTP 요청을 수행하는 API를 추가했다. 수집기 설치나 메트릭 조회 기능은 아니다.

| API | 동작 |
| --- | --- |
| `GET /api/v1/environments/{id}/data-sources` | 설정과 마지막 검사 결과 목록 |
| `PUT /api/v1/environments/{id}/data-sources/{type}` | 종류별 설정 생성·변경 |
| `POST /api/v1/environments/{id}/data-sources/{type}/test` | 저장된 주소 검사 후 결과 저장 |

V2 마이그레이션에 환경 외래키와 `(environment_id, type)` 유일 제약을 추가했다. URL·timeout이 바뀌면 이전 검사 결과를 초기화한다. 연결 검사 동안 DB 트랜잭션을 유지하지 않으며, JPA version으로 동시 변경을 감지해 오래된 결과의 덮어쓰기를 막는다.

접속은 서버 실행 환경의 정확한 base URL 허용 목록으로 제한하고 리다이렉트를 따르지 않는다. 응답 본문과 예외 상세는 저장·노출하지 않는다. 실패는 timeout, DNS, 연결 거절, TLS, HTTP 오류 등 코드로 구분한다. `CONNECTED`는 마지막 readiness 응답이 200이었다는 의미이며 데이터 수신·서비스 정상 여부를 뜻하지 않는다.

검증 결과:

- H2: 총 12개 테스트 통과(기존 환경 API 2개, 데이터 소스 API 7개, URL 정책·오류 분류 3개).
- 전용 MySQL 8.4 `sentrix_test`: 같은 12개 테스트 통과, V1→V2 마이그레이션 및 실행 JAR 패키징 성공.
- HTTP 시험 서버를 사용해 제품별 readiness 경로, 200/401/503/302, timeout, 느린 본문, 환경 간 분리, 입력 검증, 동시 설정 변경 충돌을 확인했다.
- 실제 Online Boutique 관측 저장소 연결, 인증 헤더, frontend 연결, 주기 감시 및 데이터 조회는 이번 범위에 포함하지 않았다.

실행·호출 예제와 응답 계약은 README의 데이터 소스 절에 정리했다. 다음 단계는 승인된 Prometheus를 연결하고 서비스 목록·메트릭 조회를 개발하는 것이다.

## 2026-10-28 — 서비스 목록·메트릭 조회

등록된 환경의 namespace와 승인된 Prometheus 설정으로 실제 query/query_range API를 호출한다. 이번 단계는 원본 메트릭 조회이며 Python 학습·feature 계산과 분리한다. DB 스키마 추가는 없다.

| API | 구현 |
| --- | --- |
| `GET /api/v1/environments/{id}/services` | kube-state-metrics의 실제 Deployment 목록, 생성 시각, 이름순 |
| `GET /api/v1/environments/{id}/metrics` | 서비스별 CPU cores·메모리 bytes·요청 rate·오류 ratio·p50/p90/p99 ms |

수집 저장소의 Prometheus/Collector 설정 및 telemetry_export.py를 확인했다. CPU·메모리는 cAdvisor를 Pod→ReplicaSet→Deployment 소유 관계로 연결한다. 요청 지표는 server span만 집계하며 현재 계약은 `service_namespace`, `service_name`, `SPAN_KIND_SERVER`, `STATUS_CODE_ERROR`와 밀리초 histogram이다. 서비스 이름을 고정하지 않고, trace 미계측 서비스에 요청 지표를 만들어내지 않는다.

응답 격자는 `[from,to)`, 초 단위, 최대 24시간·1440 지점이다. 실제 0과 null을 구분하며 누락은 보간하지 않는다. 모든 지점이 유한하면 AVAILABLE, 일부 누락/경고는 PARTIAL, 유한한 값이 없으면 NO_DATA다. 정상 여부나 모든 Pod의 수집 완전성을 의미하지 않는다. 통신·쿼리 오류는 빈 차트로 숨기지 않고 구분된 HTTP 오류와 code를 반환한다. 외부 응답은 4 MiB 제한과 본문까지 포함한 timeout을 적용한다.

검증:

- H2 20개 테스트 통과, 실제 MySQL 8.4 전용 테스트 DB에서도 20개 및 실행 JAR 빌드 통과.
- Prometheus v3.5.0의 promtool로 실제 코드가 생성한 쿼리 11개 사례 통과: 여러 Pod 합계, 중복 scrape, namespace·유사 서비스명 분리, client span 제외, 오류 없는 호출과 0 트래픽, histogram quantile, 미관측 서비스.
- promtool의 NaN 직접 비교는 같은 NaN도 불일치로 처리해, `count(expr != expr)`가 1인지 검증하는 방식으로 수정했다. 실제 쿼리의 0/0 결과는 유지한다.
- 같은 PromQL 검증을 CI에 추가했다. API 테스트는 upstream 실패·리다이렉트·느린 본문·과대 응답·입력 제한도 확인한다.

남은 범위: 실제 Online Boutique 클러스터 end-to-end 검증, 대시보드 연결, StatefulSet/DaemonSet 인벤토리, Pod 상태·수집 최신성·완전성, 여러 클러스터를 합친 저장소의 cluster 라벨, 인증 헤더는 아직 구현하지 않았다. 단일 클러스터·현재 Collector 지표 계약을 전제로 한다.
