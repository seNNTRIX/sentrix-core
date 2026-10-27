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

비밀번호를 바꿀 때는 동일한 `DB_PASSWORD`를 Compose와 Java 프로세스에 전달해야 합니다. Compose의 DB 초기화 환경변수는 빈 볼륨에서만 적용됩니다.

## API 확인

| Method | Path | 설명 |
| --- | --- | --- |
| GET | `/actuator/health` | 서버 및 DB 연결 상태, 정상 시 `UP` |
| POST | `/api/v1/environments` | 환경 이름·namespace 등록 |
| GET | `/api/v1/environments?page=0&size=20` | 환경 목록, 최대 50개씩 |
| GET | `/api/v1/environments/{id}` | 환경 상세 |

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

## 테스트

```sh
./mvnw test
```

기본 테스트는 H2의 MySQL 호환 모드로 API·마이그레이션을 검증합니다. 실제 MySQL과 완전히 같은 동작을 보장하지 않으므로 CI에서는 별도의 MySQL 8.4 테스트 DB로 같은 테스트를 실행합니다.

```sh
# 테스트는 환경 데이터를 지웁니다. 개발/운영 DB가 아닌 전용 DB만 지정하세요.
./mvnw test \
  -Dspring.datasource.url=jdbc:mysql://127.0.0.1:3307/sentrix_test \
  -Dspring.datasource.username=sentrix_test \
  -Dspring.datasource.password=sentrix-test-local
```

개발 기록: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)

기술 결정: [docs/decisions/001-java-mysql.md](docs/decisions/001-java-mysql.md)

프로젝트 연구 정의는 `sentrix-collection`의 프로젝트 문서와 함께 확인합니다. 모델 입력/출력 schema는 학습 측과 확정 후 구현합니다.
