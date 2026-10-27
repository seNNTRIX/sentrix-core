# sentrix-core

SentriX의 Java 21 / Spring Boot 4.1.2 백엔드. 서비스 메타데이터와 진단 결과는 MySQL 8.4에 저장합니다.

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
- 애플리케이션 및 DB 연결 상태 확인
- API 통합 테스트 및 MySQL 8.4 CI 설정

환경 등록은 이름과 Kubernetes namespace를 저장합니다. 등록만으로 수집기가 설치되거나 관측 연결이 완료되지는 않습니다. 실제 telemetry 조회, topology, 모델 호출, 진단 저장, 사용자 인증, dashboard 연결은 아직 구현 전입니다.

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

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/actuator/health` | 서버 및 DB 연결 상태, 정상 시 `UP` |
| POST | `/api/v1/environments` | 환경 이름·namespace 등록 |
| GET | `/api/v1/environments?page=0&size=20` | 환경 목록, 최대 50개씩 |
| GET | `/api/v1/environments/{id}` | 환경 상세 |
| GET | `/api/v1/environments/{id}/data-sources` | 저장된 데이터 소스 설정·최근 연결 테스트 결과 |
| PUT | `/api/v1/environments/{id}/data-sources/{type}` | 종류별 설정 생성·변경 |
| POST | `/api/v1/environments/{id}/data-sources/{type}/test` | 저장된 주소로 실제 readiness 요청 후 결과 저장 |

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

개발 기록: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)

기술 결정: [docs/decisions/001-java-mysql.md](docs/decisions/001-java-mysql.md)

프로젝트 연구 정의는 `sentrix-collection`의 프로젝트 문서와 함께 확인합니다. 모델 입력/출력 schema는 학습 측과 확정 후 구현합니다.
