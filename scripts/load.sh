#!/usr/bin/env bash
# k6 부하 Job을 (재)실행하고 로그를 따라간다. 스케일은 다른 터미널에서:
#   kubectl --context kind-chzzk get hpa,pods -w
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER="${KIND_CLUSTER_NAME:-chzzk}"
K="kubectl --context kind-${CLUSTER}"

echo "==> 스크립트 ConfigMap 갱신"
$K create configmap k6-script --from-file="$ROOT/load/k6-load.js" --dry-run=client -o yaml | $K apply -f -

echo "==> 이전 k6 Job 삭제 후 재실행"
$K delete job k6-load --ignore-not-found --wait=true
$K apply -f "$ROOT/k8s/k6-job.yaml"
$K wait --for=condition=Ready pod -l app=k6-load --timeout=180s || true

echo "==> 로그 (Ctrl-C로 로그만 끊어도 Job은 계속 돈다)"
$K logs -f job/k6-load
echo
echo "스케일 인은 부하가 끝나고 약 1분 뒤부터 시작합니다: kubectl --context kind-${CLUSTER} get hpa,pods -w"
