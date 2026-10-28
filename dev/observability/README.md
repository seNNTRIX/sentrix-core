# 로컬 로그·트레이스 조회 검증

기존 Online Boutique/k3d 환경에 Loki와 Pod 로그 수집기를 추가한다. `sentrix-collection`의 원본 배포 파일은 읽기만 하며, 이 폴더에 로컬 오버라이드를 보관한다. 아래는 sentrix-core 루트에서 실행한다. 형제 폴더에 sentrix-collection, PATH에 helm/kubectl이 필요하다.

```sh
export KUBECONFIG=/private/tmp/sentrix-local/kubeconfig
helm repo add grafana https://grafana.github.io/helm-charts
helm upgrade --install loki grafana/loki --version 7.3.0 -n sentrix \
  -f ../sentrix-collection/deploy/loki/values-minimal.yaml \
  -f dev/observability/loki-local-values.yaml --wait --timeout 5m
helm upgrade --install otel-logs open-telemetry/opentelemetry-collector --version 0.173.1 -n sentrix \
  -f ../sentrix-collection/deploy/otel-collector/daemonset-values.yaml \
  -f dev/observability/log-collector-local-values.yaml --wait --timeout 3m
kubectl -n sentrix port-forward --address 127.0.0.1 svc/loki 3100:3100
```

마지막 명령은 터미널을 유지한다. Java의 허용 URL 목록에 `http://127.0.0.1:3100`을 넣고 환경의 Loki 데이터 소스로 저장·연결 검사한다. 대시보드 `#B7-Logs?service=frontend`에서 조회한다.

수집기는 sentrix namespace의 stdout/stderr만 읽고 Loki·OTel·Prometheus 자신의 로그는 제외한다. Kubernetes 메타데이터로 실제 Deployment 이름을 얻으며 Pod 이름 접두사로 추정하지 않는다. 수집 시작 전 과거 로그 전체나 재시작 중 누락까지 보장하지 않는다. 원본 설정은 24시간 보관과 2Gi PVC를 사용한다. 운영 배포용 인증·로그 마스킹·대량 적재 내구성 설정은 포함하지 않는다.

이번 로그 API는 사람이 화면에서 검색하는 용도다. 최대 500건을 제한하므로 ML의 전체 window 로그 수·오류 로그 수 계산에 그대로 사용하면 안 된다. 모델 입력 수집은 별도 계약이 필요하다.


## Tempo 연결

기존 collector의 span metrics를 유지하면서 trace 저장을 추가한다. 아래 명령도 core 루트 기준이다.

```sh
helm upgrade --install tempo grafana/tempo --version 1.24.4 -n sentrix \
  -f dev/observability/tempo-local-values.yaml --wait --timeout 3m
helm upgrade otel-gateway open-telemetry/opentelemetry-collector --version 0.173.1 -n sentrix \
  -f ../sentrix-collection/deploy/local-demo/otel-values.yaml \
  -f dev/observability/trace-collector-local-values.yaml --wait --timeout 3m
kubectl -n sentrix port-forward --address 127.0.0.1 svc/tempo 3200:3200
```

Tempo URL은 `http://127.0.0.1:3200`이며 로컬 데이터 소스 timeout은 10초로 설정한다. backend 허용 목록에 넣고 환경의 TEMPO 소스로 저장·연결 검사한다. `#B6-Trace?service=checkoutservice`에서 checkoutservice를 지나는 요청을 찾을 수 있다. 연결 이후 새로 발생한 요청만 저장된다. 쇼핑몰을 탐색·주문하거나 기존 k6 스크립트로 짧은 정상 트래픽을 생성한다.

로컬 override는 retention 30분(정확한 즉시 삭제 시각은 compaction에 따라 다름), PVC 2Gi, 메모리 상한 768Mi, CPU 상한 1코어, 낮은 쿼리 동시성을 사용한다. 낮은 트래픽의 짧은 화면 검증용이다. 장시간 데이터셋 수집에 충분하다는 의미가 아니며 원본 collection의 3Gi 요구량을 대체하지 않는다. OOM/재시작·리소스를 확인해야 한다. Helm 1.24.4는 deprecated 안내가 있으므로 운영 배포 시 유지보수되는 배포 방식을 별도 검토한다.

현재 Online Boutique의 일부 서비스만 trace 계측되어 있다. Pod의 CPU·로그가 보여도 그 서비스의 span은 없을 수 있다. k6는 외부 parent ID를 보내므로 외부 부하 생성기 span이 저장되지 않으면 부모 누락 경고가 정상적으로 나타난다. 이것만으로 애플리케이션 장애라고 판단하지 않는다.

기동 및 raw 응답 확인 버전: Tempo 2.9.0. Pod 교체 시 port-forward도 다시 실행해야 한다.
