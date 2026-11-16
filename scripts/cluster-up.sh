#!/usr/bin/env bash
# kind 클러스터 생성(있으면 재사용) -> 이미지 빌드 -> 로드 -> Secret -> Postgres/Redis -> 앱/metrics-server/HPA.
# 필요 도구: docker, kind, kubectl (openssl). 선택: TOSS_CLIENT_KEY, TOSS_SECRET_KEY 환경변수.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER="${KIND_CLUSTER_NAME:-chzzk}"
IMAGE="${CHZZK_IMAGE:-chzzk:local}"
CTX="kind-${CLUSTER}"

for tool in docker kind kubectl openssl; do
  command -v "$tool" >/dev/null || { echo "필요한 도구가 없습니다: $tool" >&2; exit 1; }
done

if kind get clusters | grep -qx "$CLUSTER"; then
  echo "==> kind 클러스터 '$CLUSTER'가 이미 있어 재사용합니다."
else
  echo "==> kind 클러스터 '$CLUSTER' 생성"
  kind create cluster --name "$CLUSTER" --config "$ROOT/k8s/kind-config.yaml"
fi

echo "==> 이미지 빌드: $IMAGE"
docker build -t "$IMAGE" "$ROOT"
echo "==> 이미지를 kind에 로드"
kind load docker-image "$IMAGE" --name "$CLUSTER"

K="kubectl --context $CTX"

echo "==> Secret chzzk-secrets 준비"
if $K get secret chzzk-secrets >/dev/null 2>&1; then
  echo "    이미 있어 DB 비밀번호를 유지합니다."
  DB_PASSWORD="$($K get secret chzzk-secrets -o jsonpath='{.data.db-password}' | base64 --decode)"
else
  DB_PASSWORD="$(openssl rand -hex 16)"
fi
SECRET_ARGS=(--from-literal=db-password="$DB_PASSWORD")
if [[ -n "${TOSS_CLIENT_KEY:-}" ]]; then SECRET_ARGS+=(--from-literal=toss-client-key="$TOSS_CLIENT_KEY"); fi
if [[ -n "${TOSS_SECRET_KEY:-}" ]]; then SECRET_ARGS+=(--from-literal=toss-secret-key="$TOSS_SECRET_KEY"); fi
$K create secret generic chzzk-secrets "${SECRET_ARGS[@]}" --dry-run=client -o yaml | $K apply -f -

echo "==> Postgres, Redis 적용"
$K apply -f "$ROOT/k8s/postgres.yaml" -f "$ROOT/k8s/redis.yaml"
$K rollout status deployment/postgres --timeout=180s
$K rollout status deployment/redis --timeout=180s

echo "==> metrics-server, 앱, Service, HPA 적용"
$K apply -f "$ROOT/k8s/metrics-server.yaml"
$K apply -f "$ROOT/k8s/chzzk.yaml"
# 이미지를 다시 빌드해 올렸다면 새 파드로 교체한다.
$K rollout restart deployment/chzzk >/dev/null
$K rollout status deployment/chzzk --timeout=300s
$K -n kube-system rollout status deployment/metrics-server --timeout=180s

cat <<EOF

배포 완료.
  접속 주소:  http://localhost:8080  (호스트 8080 -> NodePort 30080)
  부하 실행:  scripts/load.sh
  스케일 관찰: kubectl --context $CTX get hpa,pods -w
  정리:       scripts/cluster-down.sh
EOF
