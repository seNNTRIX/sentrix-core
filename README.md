# sentrix-core

SentriX 백엔드.

> 프로젝트 기준 문서: `sentrix-collection` 저장소의 `sentrix-project-context.md`, 진행 상황: `docs/MILESTONES.md` (2026-10-20 Transfer 예정).

실시간으로 telemetry를 조회해 window feature를 만들고, model-server에 진단을 요청해 결과를 저장·제공한다.

## 범위

- Prometheus·Tempo·Loki query API로 서비스·연결별 telemetry 수집
- 30초 window, `sentrix-features`로 node/edge feature 생성 (학습과 같은 버전)
- 서비스 topology(trace에서 관측된 호출 그래프) 유지
- model-server 호출, 진단 결과(탐지 점수, 원인 후보 순위, 근거) 저장
- dashboard용 API

## 흐름

```
Prometheus / Tempo / Loki -> query -> window -> features -> model-server -> diagnosis DB -> dashboard API
```

대상 환경: Online Boutique on k3s (수집 환경과 같음). 상세 설계는 학습 결과와 API schema가 정해진 뒤 확정한다.
