# sentrix-core

**개발 진행·완료 체크·문제 해결 기록:** [백엔드 작업 현황표](docs/BACKEND-TRACKER.md). 개발할 때 관련 작업 상태와 검증 결과를 함께 갱신합니다. 과거 상세 이력은 [DEVELOPMENT.md](docs/DEVELOPMENT.md)에 보존합니다.

SentriX의 Java 21 / Spring Boot 4.1.2 백엔드. 환경·데이터 소스 설정은 MySQL 8.4에 저장합니다. ML 진단 결과 저장은 후속 개발 대상입니다.

목표 흐름:

```text
Prometheus / Tempo / Loki -> query -> window -> Python features/model-server
    -> diagnosis 저장(MySQL) -> dashboard API
```

대상은 Online Boutique on k3s입니다. 30초 window와 node/edge feature는 수집·학습 측 계약에 맞추며, Python `sentrix-features`와 같은 버전을 사용하는 분석 서버로 연계할 예정입니다. Java에서 별도의 feature 구현을 만들어 학습과 달라지게 하지 않습니다.

## 현재 구현

- MySQL 연결, Flyway 테이블 마이그레이션, JPA 스키마 검증
- 관측 환경 메타데이터 등록·목록·상세 API
- 환경별 Prometheus·Tempo·Loki 접속 설정 저장·조회 및 HTTP 연결 테스트
- Prometheus의 Deployment 목록·서비스별 메트릭 시계열·준비 상태 조회
- Loki 로그 검색, Tempo 트레이스 검색·상세 조회 및 대시보드 연결
- 애플리케이션 및 DB 연결 상태 확인
- API 통합 테스트 및 MySQL 8.4 CI 설정

환경 등록은 이름과 Kubernetes namespace를 저장합니다. 등록만으로 수집기가 설치되거나 관측 연결이 완료되지는 않습니다. 메트릭 조회는 아래의 수집 지표 계약을 사용합니다. 메트릭·준비 상태·로그·트레이스 조회와 dashboard 연결을 구현했습니다. topology, 모델 호출, 진단 저장, 사용자 인증은 아직 구현 전입니다.

## 로컬 실행

Java 21과 실행 중인 Docker가 필요합니다. 저장소 루트에서:

```sh
docker compose up -d --wait
# macOS에서 Java 버전 선택
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw spring-boot:run
```

API: `http://127.0.0.1:8080`, MySQL: `127.0.0.1:3307`.
최초 실행 시 Maven과 의존성을 다운로드하므로 인터넷 연결이 필요합니다.
MySQL 데이터는 Compose의 `mysql-data` 볼륨에 남습니다. `docker compose stop`은 데이터를 유지합니다.

현재 인증이 없으므로 기본값은 로컬 접근 전용입니다. 개발용 DB 계정은 `sentrix`, 비밀번호는 `sentrix-local`이며 Compose에 명시되어 있습니다. 운영용 자격증명이 아닙니다.

| 환경변수 | 기본값 / 용도 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://127.0.0.1:3307/sentrix` |
| `DB_USERNAME` | `sentrix` |
| `DB_PASSWORD` | `sentrix-local` |
| `SERVER_PORT` | `8080` |
| `SERVER_ADDRESS` | `127.0.0.1` |
| `SENTRIX_DATASOURCE_ALLOWED_URLS` | 서버가 접속할 수 있는 정확한 base URL의 쉼표 구분 목록. 기본값은 빈 목록(접속 불허) |

비밀번호를 바꿀 때는 동일한 `DB_PASSWORD`를 Compose와 Java 프로세스에 전달해야 합니다. Compose의 DB 초기화 환경변수는 빈 볼륨에서만 적용됩니다.

## API 확인

### Swagger에서 직접 호출

서버 실행 후 [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html)를 엽니다. OpenAPI JSON은 [문서 주소](http://127.0.0.1:8080/v3/api-docs)에서 확인합니다.

1. `00. 서버 상태` → `GET /actuator/health` → **Try it out** → **Execute**를 눌러 `UP`을 확인합니다.
2. `01. 관측 환경` → `GET /api/v1/environments`를 실행해 실제 환경 ID를 확인합니다.
3. 해당 ID로 서비스 목록·메트릭·로그·요청 추적 API를 호출합니다. 시간 조회에는 저장소에 데이터가 있는 최근 UTC 시각을 사용합니다.
4. **Server response**가 실제 호출 결과입니다. 문서의 **Example Value**는 응답 형식 예시이며 실제 관측값이 아닙니다. POST/PUT의 Execute는 실제 설정을 저장하거나 연결 검사를 실행합니다.

현재 구현한 18개 API만 문서화합니다. 한국어 기능 설명·조회 제한·오류 응답을 포함하며, 데이터 소스 종류와 메트릭 이름은 선택 목록으로 제공합니다. 서버와 같은 주소를 사용하므로 Swagger를 위해 CORS를 추가할 필요가 없습니다. 외부 Swagger 예시 문서와 외부 validator는 사용하지 않습니다. 기존 로컬 바인딩과 데이터 소스 허용 목록은 그대로 적용됩니다.

Spring Boot 4용 `springdoc-openapi-starter-webmvc-ui:3.2.0`을 사용합니다. 참고: [공식 시작 문서](https://springdoc.org/#getting-started), [호환성 안내](https://springdoc.org/faq.html).

### 구현 API 목록

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/actuator/health` | 서버 및 DB 연결 상태, 정상 시 `UP` |
| POST | `/api/v1/environments` | 환경 이름·namespace 등록 |
| GET | `/api/v1/environments?page=0&size=20` | 환경 목록, 최대 50개씩 |
| GET | `/api/v1/environments/{id}` | 환경 상세 |
| GET | `/api/v1/environments/{id}/data-sources` | 저장된 데이터 소스 설정·최근 연결 테스트 결과 |
| PUT | `/api/v1/environments/{id}/data-sources/{type}` | 종류별 설정 생성·변경 |
| POST | `/api/v1/environments/{id}/data-sources/{type}/test` | 저장된 주소로 실제 readiness 요청 후 결과 저장 |
| GET | `/api/v1/environments/{id}/collection-status?service=frontend` | 서비스별 대표 지표·로그·trace 최신성 확인 |
| GET | `/api/v1/environments/{id}/services` | 등록 namespace에서 관측되는 Deployment 목록 |
| GET | `/api/v1/environments/{id}/services/{service}/status` | 최근 Deployment 인스턴스 수·준비 상태 |
| GET | `/api/v1/environments/{id}/logs` | 서비스·기간·문자열별 Loki 로그 검색 |
| GET | `/api/v1/environments/{id}/traces` | 서비스·기간별 Tempo 요청 추적 검색 |
| GET | `/api/v1/environments/{id}/traces/{traceId}` | 환경 내 처리 구간·부모 관계·소요 시간 |
| GET | `/api/v1/environments/{id}/metrics` | 서비스·지표·기간별 시계열 조회 |

```sh
curl http://127.0.0.1:8080/actuator/health

curl -i -X POST http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' \
  -d '{"name":"Online Boutique","namespace":"sentrix"}'

curl 'http://127.0.0.1:8080/api/v1/environments?page=0&size=20'
# 생성 응답에 반환된 id로 조회
curl http://127.0.0.1:8080/api/v1/environments/1
```

등록 응답은 `201 Created`, `Location` 헤더와 `id`, `name`, `namespace`, `createdAt`을 반환합니다. 시각은 UTC입니다. 목록은 `items`, `page`, `size`, `totalElements`, `totalPages`를 반환하며 id 오름차순입니다.

이름은 앞뒤 공백 제거 후 1~100자이며 중복은 `409`입니다. MySQL 기본 collation에서는 대소문자만 다른 이름도 중복으로 처리될 수 있습니다. Namespace는 1~63자의 소문자·숫자·하이픈을 허용하고 양 끝은 소문자 또는 숫자여야 합니다. 잘못된 입력·페이지 범위는 `400`, 없는 환경은 `404`입니다.

## 데이터 소스 설정·연결 테스트

`type`은 소문자 `prometheus`, `tempo`, `loki`입니다. 환경당 종류별 1개를 저장합니다. PUT은 생성·변경 모두 `200`과 전체 설정을 반환합니다. GET은 저장된 항목의 JSON 배열이며 미등록 환경 설정은 빈 배열입니다. 존재하지 않는 환경은 `404`입니다.

먼저 **Java 백엔드에서 접근할 수 있는 주소**를 실행 환경변수에 허용하고 서버를 시작합니다. 아래 주소는 관측 저장소를 로컬 포트로 노출한 경우의 예시이며, 이 명령이 저장소나 수집기를 설치하지는 않습니다.

```sh
export SENTRIX_DATASOURCE_ALLOWED_URLS='http://127.0.0.1:9090,http://127.0.0.1:3200,http://127.0.0.1:3100'
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw spring-boot:run
```

Kubernetes 내부 DNS 주소는 해당 네트워크에 접근할 수 있는 백엔드에서만 사용할 수 있습니다. `localhost`는 사용자의 브라우저가 아니라 Java 서버 자신을 가리킵니다. 분산 구성이나 gateway를 사용할 때는 readiness 경로를 제공하는 승인된 엔드포인트를 정해야 합니다.

```sh
# 환경 1이 먼저 등록되어 있어야 합니다.
curl -X PUT http://127.0.0.1:8080/api/v1/environments/1/data-sources/prometheus \
  -H 'Content-Type: application/json' \
  -d '{"name":"Prometheus","baseUrl":"http://127.0.0.1:9090","timeoutSeconds":5}'

curl -X POST http://127.0.0.1:8080/api/v1/environments/1/data-sources/prometheus/test

curl http://127.0.0.1:8080/api/v1/environments/1/data-sources
```

`name`은 공백 제거 후 1~100자, `baseUrl`은 최대 2048자, `timeoutSeconds`는 필수 정수 1~10입니다. Base URL에는 HTTP(S)만 허용하고 사용자명·비밀번호, query, fragment 및 모호한 경로는 거부합니다. 후행 `/`, 호스트 대소문자와 기본 포트는 정규화합니다. `/prometheus` 같은 경로 prefix를 쓸 때는 허용 목록에도 같은 prefix를 넣습니다.

설정 저장은 네트워크 요청을 보내지 않습니다. URL·타임아웃이 변경되면 기존 테스트 결과를 지우고 `NOT_TESTED`로 돌아갑니다. 동일 설정 재저장이나 이름만 변경할 때는 결과를 유지합니다.

테스트 응답 예시(값은 설명용):

```json
{
  "id": 1,
  "environmentId": 1,
  "type": "prometheus",
  "name": "Prometheus",
  "baseUrl": "http://127.0.0.1:9090",
  "timeoutSeconds": 5,
  "status": "CONNECTED",
  "lastCheckedAt": "2026-10-27T12:00:00Z",
  "latencyMs": 12,
  "httpStatus": 200,
  "failureCode": null,
  "createdAt": "2026-10-27T11:59:00Z",
  "updatedAt": "2026-10-27T12:00:00Z"
}
```

| 필드 | 의미 |
| --- | --- |
| `status` | `NOT_TESTED` 미검사, `CONNECTED` 마지막 readiness 응답 200, `FAILED` 마지막 테스트 실패 |
| `lastCheckedAt` | 마지막 테스트 완료 시각(UTC). 주기적인 연결 감시가 아니라 저장된 검사 결과 |
| `latencyMs` | readiness 요청의 응답 헤더 확인 또는 실패까지 걸린 시간(ms). 설정 저장만 했으면 null |
| `httpStatus` | 상대 서버의 HTTP 상태 코드. 응답을 받지 못했으면 null |
| `failureCode` | 성공·미검사는 null, 실패는 아래 코드 |

| 실패 코드 | 의미 |
| --- | --- |
| `TIMEOUT` | 제한 시간 안에 연결·응답 헤더를 받지 못함 |
| `DNS_ERROR` | 호스트 이름 해석 실패 |
| `CONNECTION_FAILED` | 접속 거절 등 연결 실패 |
| `TLS_ERROR` | HTTPS 인증서·TLS 오류 |
| `HTTP_ERROR` | 200·리다이렉트 외 응답. 예: 인증 필요 401/403, 준비 안 됨 503 |
| `REDIRECT_REJECTED` | 3xx 응답. 다른 주소로 따라가지 않음 |
| `TARGET_NOT_ALLOWED` | 저장 후 서버 허용 목록에서 제외된 주소 |
| `IO_ERROR` | 그 밖의 통신 오류 |

테스트 실행 자체가 처리되면 연결 실패도 HTTP `200`에 `status: FAILED`로 반환하고 DB에 저장합니다. 잘못된 입력·미허용 주소 저장은 `400`, 미등록 환경·데이터 소스는 `404`입니다. 검사 중 설정 변경 등 동시 수정이 발생하면 `409`를 반환하며 오래된 결과로 새 설정을 덮어쓰지 않습니다. 검사 스레드가 중단되면 `503`이며 기존 결과를 유지합니다. 상세 예외나 상대 서버 응답 본문을 노출하지 않습니다.

검사 경로는 [Prometheus `/-/ready`](https://prometheus.io/docs/prometheus/latest/management_api/), [Tempo `/ready`](https://grafana.com/docs/tempo/latest/api_docs/), [Loki `/ready`](https://grafana.com/docs/loki/latest/reference/loki-http-api/)입니다. **성공은 설정된 readiness 경로에서 HTTP 200을 받았다는 뜻입니다.** 제품 종류, 데이터 수집 여부, 쿼리 권한, 특정 namespace 데이터 존재나 서비스 정상 여부를 증명하지 않습니다. 조회 기능에서 별도로 검증해야 합니다.

허용 목록은 API로 수정할 수 없으며 서버 실행 환경에서 관리합니다. 신뢰하는 관측 저장소만 등록해야 합니다. DNS 호스트를 허용하면 그 DNS 관리자를 신뢰하는 것이므로, 외부 사용자가 통제하는 호스트는 넣지 않습니다. 기본 인증·Bearer 토큰·tenant 헤더 및 사용자 권한 관리는 이번 범위에 없으며, 인증 없는 서버는 계속 로컬 접근 전용으로 사용합니다.

## 서비스 목록·메트릭 조회

환경에 Prometheus 주소를 먼저 저장하고 서버의 URL 허용 목록에 등록해야 합니다. 실제 질의는 매번 실행하며 이전 readiness 테스트가 성공했는지와는 독립적입니다. 조회는 MySQL에 원본 시계열을 복제하거나 마지막 readiness 결과를 수정하지 않습니다.

```sh
curl http://127.0.0.1:8080/api/v1/environments/1/services

# from/to는 실제 데이터가 보관된 시각으로 변경하세요. UTC 예시입니다.
curl -G http://127.0.0.1:8080/api/v1/environments/1/metrics \
  --data-urlencode 'service=frontend' \
  --data-urlencode 'metric=cpu_usage_cores' \
  --data-urlencode 'from=2026-10-27T12:00:00Z' \
  --data-urlencode 'to=2026-10-27T12:10:00Z' \
  --data-urlencode 'stepSeconds=30'
```

서비스 목록 응답은 `environmentId`, `namespace`, `queriedAt`, `dataStatus`, `warnings`, `items`입니다. 각 항목은 `{name, kind: "Deployment", createdAt}`이며 이름순입니다. `createdAt`은 Kubernetes Deployment 생성 시각이고, `queriedAt`은 조회 평가 시각입니다. 마지막 수집 시각이나 정상 여부로 사용하지 않습니다.

**현재 서비스 식별 단위는 Kubernetes Deployment입니다.** `kube_deployment_created`에서 실제 목록을 조회하며 11개 서비스 이름을 하드코딩하지 않습니다. 같은 namespace의 관측 도구·loadgenerator Deployment도 포함될 수 있습니다. StatefulSet·DaemonSet, Kubernetes Service 객체 목록, trace 계측 여부는 반환하지 않습니다. 준비 상태와 인스턴스 수는 별도 `/services/{service}/status`에서 조회합니다. 최대 500개이며 초과 시 오류를 반환합니다.

| `metric` | 단위 | 계산 |
| --- | --- | --- |
| `cpu_usage_cores` | cores | Pod·컨테이너 CPU counter의 1분 rate를 Deployment별 합산. 1은 CPU 코어 1개 |
| `memory_working_set_bytes` | bytes | 컨테이너 working set 합계 |
| `request_rate` | requests/second | server span 호출 counter의 1분 rate 합계 |
| `error_ratio` | ratio | 오류 server span rate / 전체 server span rate. 0.25는 25% |
| `latency_p50_ms` | milliseconds | server span histogram의 p50 |
| `latency_p90_ms` | milliseconds | server span histogram의 p90 |
| `latency_p99_ms` | milliseconds | server span histogram의 p99 |

`service`, `metric`, `from`, `to`는 필수입니다. 서비스는 Deployment 이름을 사용하고 `service_name`과 동일하다는 현재 수집 계약을 따릅니다. 기간은 **from 포함, to 제외**이며 ISO 8601 시각의 초 단위만 허용합니다. 최대 24시간, `stepSeconds` 기본 30·허용 5~3600, 최대 1440개 지점입니다. 초과하면 기간을 줄이거나 step을 늘려야 합니다. Rate 계산 창은 항상 1분이며 그래프 간격 또는 ML 학습 window와 별개입니다.

응답 예시(값은 설명용):

```json
{
  "environmentId": 1,
  "namespace": "sentrix",
  "service": "frontend",
  "metric": "cpu_usage_cores",
  "unit": "cores",
  "from": "2026-10-27T12:00:00Z",
  "to": "2026-10-27T12:01:00Z",
  "stepSeconds": 30,
  "dataStatus": "PARTIAL",
  "warnings": [],
  "points": [
    {"timestamp": "2026-10-27T12:00:00Z", "value": 0.0},
    {"timestamp": "2026-10-27T12:00:30Z", "value": null}
  ]
}
```

- `AVAILABLE`: 요청한 격자의 모든 지점에 유한한 값이 있고 upstream 경고가 없음.
- `PARTIAL`: 일부 지점 누락/비유한 값 또는 upstream 경고가 있음.
- `NO_DATA`: 유한한 지점이 하나도 없음. 없는 서비스·수집 미설정·보관 기간 밖·호출 없는 오류율 등 원인은 이 상태만으로 확정하지 않음.
- 실제 0은 0으로 유지하고 누락은 null로 반환합니다. 보간·0 채우기를 하지 않습니다. `NaN`·`+Inf`·`-Inf`는 null과 `NON_FINITE_VALUES` 경고로 변환합니다.
- Prometheus 경고/정보 메시지는 `UPSTREAM_WARNING`으로 표시합니다. 서버의 원문 메시지·쿼리·내부 상세를 노출하지 않습니다.

`AVAILABLE`은 서비스 정상 또는 수집 최신성을 뜻하지 않습니다. Prometheus의 lookback 안에 남은 이전 샘플도 평가에 사용될 수 있습니다. 모든 Pod의 수집 완전성도 이 상태로 보장하지 않습니다. 신규 수집/중단과 실제 0 트래픽을 확정하려면 이후 수집 상태 기능이 필요합니다.

### 서비스 준비 상태

```sh
curl http://127.0.0.1:8080/api/v1/environments/1/services/frontend/status
```

`environmentId`, `namespace`, `service`, `queriedAt`, `observedAt`, `maxAgeSeconds=60`, `status`, `desiredReplicas`, `replicas`, `readyReplicas`, `availableReplicas`, `warnings`를 반환합니다. 상태를 MySQL에 복사하지 않고 등록된 Prometheus에서 조회합니다.

- `desiredReplicas`: 목표 Pod 수. `replicas`: Kubernetes가 집계한 현재 Pod 수이며 프로세스가 정상 실행 중이라는 보장은 아닙니다.
- `readyReplicas`: 준비된 Pod 수. `availableReplicas`: Kubernetes의 최소 준비 유지 시간 조건까지 만족한 가용 Pod 수.
- `READY`: 목표가 1 이상이고 준비·가용 수가 모두 목표 이상. 추가 Pod가 생기는 롤링 배포도 허용합니다.
- `SCALED_DOWN`: 목표와 현재 수가 모두 0. 0이라는 이유만으로 장애로 분류하지 않습니다.
- `NOT_READY`: 유효한 집계는 있지만 위 두 조건에 해당하지 않음. 시작·축소·배포 중일 수 있으므로 장애 확정이 아닙니다.
- `UNKNOWN`: 필요한 지표 누락, 60초 초과, 중복 수집 값의 불일치, 수 간 모순, upstream 경고.

4종 kube-state-metrics 지표의 min/max를 비교해 중복 수집을 합산하지 않습니다. 모든 원본 시각 중 가장 오래된 시각을 `observedAt`으로 반환합니다. `timestamp(원본 지표)`를 사용하므로 쿼리 평가 시각을 마지막 수집 시각으로 오해하지 않습니다. 오래됐거나 누락·충돌한 필드는 `null`이며 실제 0은 보존합니다. 네 지표가 있어도 `가용 > 준비` 또는 `준비 > 현재`이면 `UNKNOWN`입니다.

경고 코드는 `MISSING_METRICS`, `STALE_METRICS`, `INCONSISTENT_METRICS`, `UPSTREAM_WARNING`입니다. 미관측·삭제된 서비스는 지표가 없으므로 `UNKNOWN`이지 존재하지 않는다는 확정 404가 아닙니다. 없는 환경은 404, 연결 미설정은 409, upstream 실패는 502/504로 기존 조회 API와 같습니다. 잘못된 개수·응답은 502입니다.

**이 상태는 최근 수집된 Kubernetes 준비 상태이며, 요청 성공·최신 배포 완료·RCA를 보장하지 않습니다.** 수집 간격은 60초보다 충분히 짧아야 합니다. 현재 로컬 구성은 5초입니다. `observedAt`은 Prometheus가 관측한 원본 샘플 시각이며 kube-state-metrics 내부 캐시나 Kubernetes 제어부의 지연까지 검증하지는 않습니다.

### 지표·서비스 연결 계약

현재 [수집 설정](https://github.com/seNNTRIX/sentrix-collection/blob/main/deploy/prometheus/values-minimal.yaml), [지표 추출 정의](https://github.com/seNNTRIX/sentrix-collection/blob/main/scripts/telemetry_export.py), [Collector 설정](https://github.com/seNNTRIX/sentrix-collection/blob/main/deploy/otel-collector/gateway-values.yaml)을 기준으로 합니다.

- 인벤토리: `kube_deployment_created{namespace=...}`. kube-state-metrics 필요.
- CPU·메모리: cAdvisor 지표의 `namespace,pod,container`와 `kube_pod_owner`, `kube_replicaset_owner`를 조인합니다. Pod→ReplicaSet→Deployment 소유 관계를 사용하며 Pod 이름 접두사로 추측하지 않습니다. 서로 다른 scrape job의 동일 컨테이너는 max로 중복 제거하고 ReplicaSet 교체 중 여러 Pod는 합산합니다.
- 요청 지표: `traces_span_metrics_calls_total`, `traces_span_metrics_duration_milliseconds_bucket`의 `service_namespace,service_name,span_kind,status_code,le` 사용. `span_kind="SPAN_KIND_SERVER"`만 포함해 client/internal span 중복을 피합니다. 오류는 `status_code="STATUS_CODE_ERROR"`이며 HTTP 5xx 비율과 동일하다고 가정하지 않습니다.
- 전체 호출 rate가 있을 때만 누락된 오류 counter를 0으로 처리합니다. 전체 rate가 0인 오류율은 계산 불가(null)입니다. latency도 개별 Pod p99 평균이 아닌 histogram bucket rate 합산 후 계산합니다.
- 단일 클러스터의 Prometheus를 전제로 합니다. 여러 클러스터의 같은 namespace를 모은 저장소에는 cluster 구분 계약이 추가로 필요합니다. Collector 버전이 지표 이름/단위/라벨을 바꾸거나 OTel 서비스 이름과 Deployment 이름이 다르면 매핑을 수정해야 합니다. 현재 trace 미계측 서비스에는 요청 지표가 없을 수 있습니다.

Prometheus의 [query/query_range API](https://prometheus.io/docs/prometheus/latest/querying/api/)를 사용합니다. 사용자가 임의 PromQL을 전달할 수 없고 등록 namespace와 검증된 서비스 이름으로 고정 쿼리를 생성합니다. 응답은 최대 4 MiB, 전체 응답 본문까지 설정된 timeout으로 제한하며 리다이렉트를 따르지 않습니다.

| HTTP / `code` | 의미 |
| --- | --- |
| 400 / `INVALID_QUERY`, `INVALID_METRIC` | 잘못된 서비스·지표·기간·격자. 필수 파라미터 누락/타입 변환 실패도 400 |
| 404 / `ENVIRONMENT_NOT_FOUND` | 환경 미등록 |
| 409 / `PROMETHEUS_NOT_CONFIGURED`, `TARGET_NOT_ALLOWED` | Prometheus 미설정 또는 URL 허용 취소 |
| 502 / `PROMETHEUS_HTTP_ERROR` | 상대 서버 비정상 HTTP 상태, `upstreamStatus` 함께 제공 |
| 502 / `PROMETHEUS_UNAVAILABLE`, `PROMETHEUS_QUERY_FAILED` | 통신 실패 또는 Prometheus 쿼리 실행 실패 |
| 502 / `INVALID_PROMETHEUS_RESPONSE`, `RESPONSE_TOO_LARGE`, `TOO_MANY_SERVICES` | 잘못되거나 제한을 초과한 응답 |
| 504 / `PROMETHEUS_TIMEOUT` | 응답 본문을 포함한 조회 제한 시간 초과 |
| 503 / `QUERY_INTERRUPTED` | 서버 요청 처리 중단 |

접속·쿼리 오류를 200/빈 데이터로 숨기지 않습니다. 설정 오류를 고친 뒤 동일 조회를 다시 요청하면 됩니다.

## 테스트

```sh
./mvnw test
```

기본 테스트는 H2의 MySQL 호환 모드로 API·마이그레이션을 검증합니다. 실제 MySQL과 완전히 같은 동작을 보장하지 않으므로 CI에서는 별도의 MySQL 8.4 테스트 DB로 같은 테스트를 실행합니다.

```sh
# 테스트는 환경·데이터 소스 데이터를 지웁니다. 개발/운영 DB가 아닌 전용 DB만 지정하세요.
./mvnw test \
  -Dspring.datasource.url=jdbc:mysql://127.0.0.1:3307/sentrix_test \
  -Dspring.datasource.username=sentrix_test \
  -Dspring.datasource.password=sentrix-test-local
```

연결 테스트는 로컬 HTTP 시험 서버를 열어 세 제품의 readiness 경로, 성공·503·401·리다이렉트·타임아웃, 느린 본문, 결과 저장과 동시 변경 충돌을 검증합니다. H2/MySQL 테스트에서 실제 관측 저장소나 팀원의 학습 환경에는 접속하지 않습니다.

서비스·메트릭 API 테스트는 namespace 격리, 정상/누락/0/NaN 처리, 조회 제한, upstream 오류, 응답 크기와 본문 timeout을 검증합니다. Maven 테스트가 생성한 실제 쿼리를 Prometheus 엔진에서 검증하려면:

```sh
docker run --rm --entrypoint /bin/promtool \
  -v "$PWD/target:/tests:ro" prom/prometheus:v3.5.0 \
  test rules /tests/prometheus-query-tests.yaml
```

CI에서도 같은 검증을 실행합니다. 여러 Pod와 중복 scrape, 다른 namespace/유사 서비스명, client span 제외, 오류율 분모 0, histogram quantile을 확인합니다. 이는 실제 Online Boutique 설치에 대한 end-to-end 검증과 구분됩니다.

개발 기록: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)

기술 결정: [docs/decisions/001-java-mysql.md](docs/decisions/001-java-mysql.md)

프로젝트 연구 정의는 `sentrix-collection`의 프로젝트 문서와 함께 확인합니다. 모델 입력/출력 schema는 학습 측과 확정 후 구현합니다.


## 서비스 로그 조회

`GET /api/v1/environments/{id}/logs?service=frontend&from=2026-10-28T09:00:00Z&to=2026-10-28T09:15:00Z&search=error&limit=100`

- 환경에 저장된 Loki와 서버 허용 URL 목록을 사용한다. namespace는 환경 정보로 결정하며 입력으로 받지 않는다.
- 필수: `service`, `from`, `to`. from 포함·to 제외, 최대 24시간. `search`는 최대 200자, 제어문자 금지, 대소문자를 구분하는 원문 포함 검색이다. 임의 LogQL은 허용하지 않는다.
- `limit` 기본 100, 범위 1~500. limit+1건을 조회해 초과 여부를 판단하고 `truncated=true`, `LIMIT_REACHED`를 표시한다. 전체 건수나 페이지 이동을 보장하지 않는다.
- 응답: 환경·서비스·조회 조건, `queriedAt`, `dataStatus`, `warnings`, `truncated`, `items`. 각 item은 `timestamp`, 나노초 문자열 `timestampNanos`, `pod`, `container`, 원문 `message`다. 여러 stream을 합쳐 최신순 정렬하며 중복 메시지를 임의로 제거하지 않는다.
- 수집 계약: `k8s_namespace_name`, `k8s_deployment_name` 인덱스 라벨. Pod·컨테이너는 라벨 또는 structured metadata에서 읽는다. 다른 namespace/서비스의 응답은 거부한다.
- `NO_DATA`는 조건에 맞는 로그가 없다는 의미다. 수집 정상·서비스 정상으로 해석하지 않는다. 저장소 오류를 빈 결과로 바꾸지 않는다.
- 409 `LOKI_NOT_CONFIGURED`/`TARGET_NOT_ALLOWED`, 502 `LOKI_HTTP_ERROR`/`LOKI_QUERY_FAILED`/`LOKI_UNAVAILABLE`/`INVALID_LOKI_RESPONSE`/`RESPONSE_TOO_LARGE`, 504 `LOKI_TIMEOUT`. 없는 환경은 404, 잘못된 조건은 400.
- Prometheus/Loki는 같은 4MiB 응답 상한·전체 본문 timeout·리다이렉트 금지 구현을 사용한다. 로그는 MySQL에 복사하지 않는다.

로컬 실행: [dev/observability/README.md](dev/observability/README.md). 이 API는 화면 검색용이며, 전체 window를 사용해야 하는 ML feature 수집 API가 아니다.


## 요청 추적 조회 (Tempo)

```http
GET /api/v1/environments/1/traces?service=checkoutservice&from=2026-10-28T10:00:00Z&to=2026-10-28T10:15:00Z&limit=20
GET /api/v1/environments/1/traces/11111111111111111111111111111111
```

- TEMPO 소스 등록 필요. `from/to`는 초 단위 UTC, 최대 24시간, `limit`은 1~100(기본 20). 검색은 TraceQL의 `resource.service.namespace`와 `resource.service.name`을 같은 span에서 정확히 일치시킨다. 이름을 요청 경로·쿼리에 직접 삽입하지 않는다.
- 검색 결과 `items`: `traceId`, `startedAt`, `durationMs`, `rootService`, `rootOperation`. 시작 서비스/작업명과 시간폭은 Tempo의 trace 전체 메타데이터이며 선택한 서비스의 처리 시간만을 뜻하지 않는다. Tempo가 먼저 찾은 N건을 반환하므로 정렬은 반환된 집합 안에서만 보장한다. `limitReached=true`는 상한 도달이며 전체 건수나 다음 페이지 존재를 확정하지 않는다. 빈 결과는 `NO_DATA`.
- 상세 `spans`: `spanId`, `parentSpanId`, `service`, `operation`, `kind`, `status`, `startedAt`, `endedAt`, `durationMs`. trace ID는 0이 아닌 32자리 소문자 hex. Tempo의 base64/hex ID를 표준 hex로 변환한다. 시각은 나노초를 보존한다.
- 상세는 환경의 `service.namespace`와 일치하는 span만 반환한다. 다른 환경/namespace 누락 span은 제외하고 경고한다. 보이는 span에 부모가 없으면 `MISSING_PARENT_SPANS`, Tempo 부분 응답이면 `UPSTREAM_PARTIAL`. `AVAILABLE`도 계측되지 않은 구간이 없다는 보장은 아니다. 상태 `UNSET`은 정상 확정이 아니다.
- 상세 `durationMs`는 **해당 환경에서 보이는 구간의 처음~끝 시간폭**이다. 중첩·병렬 구간 시간을 합산하지 않는다. 느리거나 오류인 span이 실제 장애 원인이라고 판정하지 않는다.
- 저장소 응답 4MiB, 상세 5,000 span 제한. 과대·손상 응답, ID 불일치, 중복 ID, 부모 순환을 거부한다. 미수집/만료/다른 환경의 trace는 `404 TRACE_NOT_FOUND`; 저장소 장애는 502/504, 미설정은 409. 임의 URL·리다이렉트는 기존 허용 목록 정책으로 차단한다.
- 원본은 Tempo에 보관하고 MySQL에 복제하지 않는다. 이 제한된 화면 조회 API는 ML 학습/전체 window 수집 용도가 아니다. 운영 환경의 사용자 인증·tenant 격리는 별도 작업이다.
- 로컬 설치: [dev/observability/README.md](dev/observability/README.md). API 형식 참고: [Tempo HTTP API](https://grafana.com/docs/tempo/latest/api_docs/). 테스트 대상 chart 1.24.4의 v2 trace 응답을 사용한다.


## 수집 데이터 최신성 확인

```http
GET /api/v1/environments/1/collection-status?service=frontend
```

환경 내 서비스 하나의 최근 데이터 증거를 조회한다. `service`는 필수이며 기존 Deployment/service.name 계약을 따른다. 저장소마다 조회하며 DB 설정이나 마지막 연결 검사 결과를 변경하지 않는다. 현재 시각 기준 최근 30분을 조회하고 180초를 최신성 기준으로 사용한다. 180초는 초기 화면 표시 기준이며 ML 임계값이나 장애 판정 기준이 아니다.

| 응답 | 의미 |
|---|---|
| `from`, `to` | 공통 조회 구간. age와 최신성 판정의 기준 시각은 `to` |
| `checkedAt` | 모든 조회를 마친 시각 |
| `lookbackSeconds`, `freshnessThresholdSeconds` | 각각 1800, 180 |
| `signals[].signal` | deployment_metrics, request_metrics, logs, traces |
| `queryStatus` | OK / PARTIAL / ERROR / NOT_CONFIGURED |
| `freshness` | RECENT / STALE / NO_DATA / UNKNOWN |
| `observedAt`, `ageSeconds` | 관측한 원본 시각과 `to` 대비 나이(초, 소수점 버림). 모르는 값은 null |
| `timestampKind` | SAMPLE_TIME / LOG_EVENT_TIME / TRACE_START_TIME |
| `failureCode`, `warnings` | 조회 실패 코드와 판정 해석에 필요한 경고 |

- **RECENT**: 180초 이내의 증거가 있다. 지속 수집·전체 Pod 계측·서비스 정상 판정은 아니다.
- **STALE**: 조회 범위에서 찾은 데이터가 180초보다 오래됐다. 수집기 고장으로 단정하지 않는다.
- **NO_DATA**: 이번 구간에서 대상 데이터를 찾지 못했다. 무트래픽·미계측·수집 중단·보관 만료 중 어떤 이유인지 이 응답만으로 확정하지 않는다.
- **UNKNOWN**: 미설정, 조회 실패, 부분 검색, 미래 시각, 조회 구간 밖에서 시작한 trace 등 판정할 근거가 부족하다.

Prometheus는 `kube_deployment_status_replicas`와 `traces_span_metrics_calls_total`의 원본 `timestamp()`를 조회한다. 30분/30초 간격 이력과 현재 샘플을 함께 확인하며 평가 시각으로 오래된 원본을 새 데이터처럼 만들지 않는다. 0인 샘플도 수집 증거다. 대표 시리즈 기준이므로 CPU·메모리·모든 Pod의 수집 완전성까지 증명하지 않는다(`REPRESENTATIVE_SERIES_ONLY`). 요청 counter가 변하지 않아도 scrape가 지속되면 RECENT일 수 있다(`SCRAPE_TIME_NOT_REQUEST_ACTIVITY`).

Loki는 namespace와 Deployment를 고정하고 최신 로그 한 건의 이벤트 시각만 반환한다. 로그 원문은 이 API에 반환하지 않는다. Tempo는 같은 span의 namespace·서비스 조건과 `most_recent=true`, limit=1로 검색한다. 반환된 매칭 span의 속성도 확인한다. 최신 **trace 시작 시각**을 쓰므로 해당 서비스 span의 시작·수신 시각과 다를 수 있다. 로그·trace 모두 저장소에 도착한 시각이 아니다(`EVENT_TIME_NOT_INGESTION_TIME`). Tempo의 최신 검색 힌트를 지원하지 않는 버전은 ERROR/UNKNOWN으로 표시하며 처음 발견한 trace를 최신으로 대체하지 않는다.

불완전 검색은 `UPSTREAM_PARTIAL`, 미래 원본 시각은 `FUTURE_TIMESTAMP`, 구간 이전에 시작한 trace는 `TRACE_START_OUTSIDE_WINDOW`로 표시한다. NO_DATA/STALE은 `CAUSE_UNDETERMINED` 경고를 포함한다. 이는 별도의 수집기 heartbeat나 계측 목록을 대신하지 않는다.

저장소 실패·미설정은 **HTTP 200의 해당 signal**에 담고 다른 저장소 결과는 유지한다. 환경 없음은 404, 잘못된 서비스/누락 입력은 400, 조회 작업 중단은 503이다. 기존 URL 허용 목록, 리다이렉트 금지, 4MiB 응답 상한을 적용한다. 한 요청은 Prometheus 1회, Loki 1회, Tempo 1회를 순차 조회하므로 최대 각 source timeout의 합계(30초)만큼 걸릴 수 있다. 자동 반복 감시·DB 이력 저장·프론트 카드 추가는 이번 범위가 아니다.

공식 근거: [Prometheus timestamp 함수](https://prometheus.io/docs/prometheus/latest/querying/functions/#timestamp), [Tempo 최신 검색 힌트](https://grafana.com/docs/tempo/latest/traceql/construct-traceql-queries/#retrieve-most-recent-results-experimental). 현재 로컬 Tempo 2.9.0에서 실제 응답 지원을 확인했다.

## 요청 단위 서비스 맵 (1차)

`GET /api/v1/environments/{id}/service-map?traceId={32자리 소문자 hex}`

기존 trace 상세 검증과 환경 필터를 재사용한다. 반환 scope는 SINGLE_TRACE다. nodes는 해당 trace의 service.name이며 Deployment나 업무 서비스로 자동 분류하지 않는다. edges는 직접 연결된 CLIENT 부모 → SERVER 자식만 포함한다. 부모 누락·미계측·비동기 관계를 추측하지 않는다.

각 edge의 observedCalls는 이 trace에서 관측한 연결 수, explicitErrorCount는 SERVER의 명시적 ERROR 수, unsetStatusCount는 판정되지 않은 상태 수다. meanServerDurationMs/maxServerDurationMs는 SERVER span 시간이다. 전체 트래픽의 호출량·오류율·네트워크 왕복 시간이 아니다. 연결이 없으면 NO_OBSERVED_EDGES, 있으면 범위가 제한되어 PARTIAL을 반환한다. 기존 trace 경고도 보존한다.

입력 400, 환경/trace 없음 404, Tempo 미설정 409, 저장소 실패 502/504, 중단 503은 기존 상세 조회 계약과 같다. 실제 환경 조회 성공 검증은 클러스터 복구 후 필요하다. 전체 기간 서비스 맵과 Deployment 매핑은 후속이다.

## 분석 실행 설정 (모델 미연결)

- `GET /api/v1/environments/{id}/monitoring`: 저장된 설정 조회. 미설정 기본값은 enabled=false, intervalSeconds=60.
- `PUT /api/v1/environments/{id}/monitoring`: `{ "enabled": true, "intervalSeconds": 60 }` 저장. 두 필드 필수, 간격 30~3600초. 환경 없음 404, 입력 오류 400.
- enabled는 사용자의 실행 의도다. 모델 미연결이므로 true일 때 실행 상태는 BLOCKED_MODEL_UNAVAILABLE, false는 DISABLED다. lastSuccessfulAt은 null이다. 실제 스케줄러·추론·장애 생성은 구현 전이며 성공을 꾸미지 않는다.
- V3 migration으로 설정을 MySQL에 저장한다. 최초 동시 저장은 환경 행 잠금으로 직렬화한다.

## Deployment 소속 Pod 목록

`GET /api/v1/environments/{id}/services/{service}/pods`

service는 Deployment 이름이다. namespace 내 kube_pod_owner와 kube_replicaset_owner의 controller 소유 관계를 조인하며 이름 접두사로 추정하지 않는다. Pod 이름과 UID, Ready 조건, 현재 관측된 일반 컨테이너의 재시작 수 합계를 반환한다. UID로 재생성된 Pod를 구분한다. init 컨테이너는 제외하며 일부 컨테이너 지표 누락 시 완전한 합계임을 보장하지 않는다.

원본 샘플이 조회 기준 60초 이내인 경우만 사용한다. 결측 ready/restarts는 null, 실제 false/0과 구분한다. 최대 500 Pod다. 빈 목록은 NO_DATA로 반환하며 미배포·스케일0·수집 누락을 구분하지 않는다. AVAILABLE은 서비스 건강 판정이 아니다. 경고 CURRENT_SAMPLES_ONLY는 현재 샘플만 사용하는 제한을 나타낸다. 과거 이력·Pod 지표 시계열은 별도 API다.
