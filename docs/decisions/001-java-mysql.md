# 001 — Java Spring Boot와 MySQL

- 날짜: 2026-10-27
- 상태: 사용자 결정으로 확정

## 결정

제품 백엔드는 Java 21 / Spring Boot, 관계형 저장소는 MySQL 8.4(InnoDB)를 사용한다. Flyway로 스키마 변경 이력을 관리하고 Hibernate는 validate만 수행한다. 개발 중 DB는 Docker Compose로 실행한다.

MySQL에는 환경·서비스 메타데이터, 장애 이력, 분석 요청·진행 상태·후보 순위·근거를 저장한다. 원본 메트릭·로그·트레이스는 기존 Prometheus·Loki·Tempo를 조회한다. 학습용 Parquet 파일을 MySQL에 복제하지 않는다.

환경→장애→분석→원인 후보의 관계와 트랜잭션이 주 요구사항이다. 가변적인 근거는 필요한 범위에서 JSON 컬럼을 사용할 수 있다. 현재 요구에 PostgreSQL 전용 기능이 필요하지 않아 기존 MySQL 선택을 유지한다.

## 경계

학습과 feature 계산은 Python 영역이다. 백엔드는 합의된 API로 분석을 요청한다. 최초 릴리스는 통제된 Online Boutique 환경을 대상으로 하며 다중 고객용 SaaS를 전제하지 않는다. 인증이 구현되기 전 서버는 loopback에 바인딩한다.
