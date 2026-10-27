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
