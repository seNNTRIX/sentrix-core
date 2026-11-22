# Java ↔ Python 분석 계약 초안

상태: **2026-11-22, 백엔드 구현을 위한 제안. 모델팀과 합의된 계약이 아니며 실제 Python 모델 연동은 미검증이다.** 계약 버전은 `sentrix-analysis-v1-draft`다. 기본 실행 설정은 비활성이며 예시 응답을 운영 결과로 생성하지 않는다.

## 책임과 현재 구현 경계

- Java는 환경·장애·작업 ID와 분석 구간을 고정하고, 작업을 저장한 뒤 Python에 분석을 요청한다. 모델의 원인 순위를 Java가 다시 계산하지 않는다.
- Python 분석 서버는 운영자가 환경 ID·namespace에 매핑한 관측 저장소에서 **분석 전용 조회**를 수행하고 학습과 동일한 feature 코드를 사용한다. 원본 조회와 feature 계산을 포함해 하나의 요청으로 응답한다는 이 초안의 제안이며, 해당 Python 구현은 아직 없다.
- 요청에 저장소 URL·인증정보·학습 정답·수동 장애 제목을 보내지 않는다. Python의 환경 매핑과 접근 제한은 모델 서버에서 별도로 구현해야 한다.
- 화면용 `/metrics`, `/logs`, `/traces`는 입력 데이터셋으로 사용하지 않는다. 검색 상한과 집계 정의가 학습용과 다르다.
- `sentrix-collection`에는 오프라인 export → canonical → RE2 projection 구현이 있다. 공유 feature 패키지, 온라인 정상 기준선, 모델 서버 구현을 이 백엔드 변경이 완성하지는 않는다.

## 설정

설정은 서버 운영자만 바꾼다. 사용자 요청으로 모델 URL을 받지 않는다.

| 환경변수 | 기본값 | 조건 |
|---|---|---|
| `SENTRIX_ANALYSIS_ENABLED` | `false` | 실제 계약을 구현한 Python 서버가 준비된 뒤 활성화 |
| `SENTRIX_ANALYSIS_BASE_URL` | 빈 값 | HTTP(S), 사용자정보·query·fragment 금지. 선택적으로 단순 path prefix 허용 |
| `SENTRIX_ANALYSIS_MODEL_VERSION` | 빈 값 | 영숫자로 시작, 영숫자·점·밑줄·하이픈, 최대 100자 |
| `SENTRIX_ANALYSIS_FEATURE_SCHEMA_VERSION` | 빈 값 | 모델 버전과 같은 문자 제한 |
| `SENTRIX_ANALYSIS_TIMEOUT_SECONDS` | `10` | 1~60초. 응답 본문 수신까지 포함 |

활성화에는 URL과 두 버전이 모두 필요하다. 잘못된 설정은 시작 시 거부한다. 서버는 `<base-url>/v1/analyses`로 JSON을 POST하며 리다이렉트는 따라가지 않는다. 응답 본문은 최대 1 MiB다. 현재 이 초안은 별도 모델 서버 인증 헤더를 제공하지 않으므로 통제된 내부 환경용이다.

## 요청

```json
{
  "contractVersion": "sentrix-analysis-v1-draft",
  "jobId": "e7600dbc-ebee-4434-84a3-4050a25cad9d",
  "environmentId": 1,
  "namespace": "sentrix",
  "windowStart": "2026-11-22T05:00:00Z",
  "windowEnd": "2026-11-22T05:00:30Z",
  "featureSchemaVersion": "agreed-feature-version",
  "modelVersion": "agreed-model-version"
}
```

- 구간은 UTC 초 정밀도의 `[windowStart, windowEnd)`이며 정확히 30초다. 종료 시각은 Unix epoch 10초 배수이고 최소 `1970-01-01T00:00:30Z`, 최대 현재 시각이다. 이 요청의 구간은 한 작업의 관측 구간으로 고정된다.
- 공개 작업 요청에서 구간을 생략하면 장애 발생 시각을 포함하는 30초 구간을 선택한다. 종료 시각은 `floor(발생 시각 epoch / 30) × 30 + 30`초다. 작업 실행은 종료 후 30초의 수집 대기 시간을 두며, 이 대기 시간이 수집 완전성을 보장하지 않는다.
- 모든 식별자는 작업 추적과 데이터 격리용이다. 서비스명·ID·절대시각을 학습 feature로 사용한다는 뜻이 아니다.
- 동일 `jobId`는 같은 구간·버전 요청을 뜻한다. Python 서버는 재전송을 중복 처리하지 않도록 구현해야 한다. 백엔드는 네트워크 오류에 대해 암묵적으로 추론을 재요청하지 않는다.
- 학습 설계의 **30초 window / 10초 stride**와 이 수동 작업 요청은 구분한다. 자동 스케줄링의 10초 stride, 수집 지연 여유, 밀린 구간 처리 정책은 후속 합의 대상이다. 기존 화면의 `intervalSeconds`를 학습 stride라고 간주하지 않는다.

## 응답

HTTP 200의 JSON 객체이며 요청의 8개 필드를 동일한 값으로 돌려준다. `decision`, `score`, `candidates`, `abstainReason`을 추가한다. 아래는 형식 설명용이며 모델의 측정 결과가 아니다.

```json
{
  "contractVersion": "sentrix-analysis-v1-draft",
  "jobId": "e7600dbc-ebee-4434-84a3-4050a25cad9d",
  "environmentId": 1,
  "namespace": "sentrix",
  "windowStart": "2026-11-22T05:00:00Z",
  "windowEnd": "2026-11-22T05:00:30Z",
  "featureSchemaVersion": "agreed-feature-version",
  "modelVersion": "agreed-model-version",
  "decision": "ABSTAIN",
  "score": null,
  "candidates": [],
  "abstainReason": "FEATURE_CONTRACT_UNAVAILABLE"
}
```

| 필드 | 조건 |
|---|---|
| `decision` | `ANOMALY`, `NORMAL`, `ABSTAIN` |
| `score` | 유한한 JSON 숫자 또는 명시적 null. 확률이나 0~1로 가정하지 않음 |
| `candidates` | 최대 20개. ANOMALY에는 1개 이상, NORMAL·ABSTAIN은 빈 배열 |
| `abstainReason` | ABSTAIN에는 공백 아닌 문자열(최대 500자), 그 외 명시적 null |

후보의 모든 필드를 제공한다. 사용하지 않는 대상 필드는 null이다.

| 후보 필드 | 조건 |
|---|---|
| `rank` | 배열 순서대로 1부터 연속된 정수 |
| `kind` | `NODE` 또는 `EDGE` |
| `service` | NODE 서비스명, 최대 253자. EDGE이면 null |
| `source`, `target` | EDGE의 방향성 있는 호출 양 끝, 각각 최대 253자·서로 다른 값. NODE이면 null |
| `faultType` | 최대 100자. 최종 분류 집합과 UNKNOWN 표현은 모델팀 합의 필요 |
| `score` | 유한한 JSON 숫자. 후보의 순위 점수이며 확률로 표시하지 않음 |
| `evidence` | 최대 20개 문자열, 각각 최대 1,000자. 원본 자격증명·개인정보·수집 원문을 포함하지 않도록 Python에서 정제 |

같은 NODE 또는 같은 방향의 EDGE 후보를 중복 반환할 수 없다. 판정과 후보 배열이 모순되거나 작업·환경·구간·버전이 요청과 다르면 전체 결과를 거부한다. 판단 유보는 정상 판정으로 변환하지 않는다. 저장된 수동 장애의 종료 여부는 이 응답만으로 자동 변경하지 않는다.

## 오류

| 코드 | 의미 |
|---|---|
| `MODEL_UNAVAILABLE` | 비활성 또는 통신 불가 |
| `INVALID_ANALYSIS_REQUEST` | 내부 요청 구간·환경·버전 형식 오류 |
| `MODEL_TIMEOUT` | 연결 또는 본문 수신 시간 초과 |
| `MODEL_REDIRECT_REJECTED` | 3xx 응답 |
| `MODEL_HTTP_ERROR` | 200 이외 응답 |
| `MODEL_RESPONSE_TOO_LARGE` | 1 MiB 초과 |
| `INVALID_MODEL_RESPONSE` | JSON·필드·식별자·버전·구간·후보 검증 실패 |
| `ANALYSIS_INTERRUPTED` | 실행 스레드 중단 |

실패 사유에는 상대 서버의 원문·URL·예외를 포함하지 않는다. HTTP 200이더라도 응답 검증을 통과해야 성공 결과로 취급한다. 취소된 작업에 뒤늦게 도착한 응답의 저장 여부는 Java 작업 상태의 조건부 갱신으로 결정한다.

## 모델팀과 확정할 사항

근거는 `sentrix-collection/campaigns/linux-v1/feature-schema.yaml`, `docs/PROJECT-DIRECTION.md`, `docs/RE2_SCHEMA.md`와 이 저장소의 `docs/decisions/001-java-mysql.md`다.

1. 실제 배포할 shared feature 코드 버전·모델 아티팩트·정규화 아티팩트와 그 호환성. YAML의 `schema_version: 1`만으로 학습 코드가 준비됐다고 판단하지 않는다.
2. 5초 수집, 30초 window, 최소 metric 표본 4개/기대 6개. 실제 원본 시각과 forward-fill 표본을 구분한다.
3. trace p50 최소 5개, p90 최소 20개, p99 최소 100개. 부족하면 null과 mask를 전달한다. 전용 조회의 상한·부분 수집을 숨기지 않는다.
4. node/edge별 정상 기준선 median·IQR과 zero-IQR 처리, 정상 구간 선택 방법·길이·갱신 정책. 미래 표본과 주입 정답을 사용하지 않는다.
5. 필수 신호 부족·서비스 식별 실패·정상 기준선 부재 시 ABSTAIN 정책과 사유. 결측을 0으로 바꾸지 않는다.
6. 모델 score 의미·임계값·faultType 집합·근거 형식, 서비스와 경로 후보의 점수 비교 가능성.
7. 환경별 원본 저장소 매핑, namespace 격리, 요청 중복 처리, 시간·응답 크기 예산. 오프라인 exporter의 실험 trace ID 목록은 온라인 전체 수집을 대체하지 않는다.

위 항목은 이번 Java 프로토콜 구현만으로 검증되지 않는다. 실제 Python 연동 전에 동일 구간을 학습 feature 코드와 온라인 경로로 계산해 일치 여부를 확인해야 한다.
