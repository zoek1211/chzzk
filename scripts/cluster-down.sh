#!/usr/bin/env bash
# kind 클러스터 삭제(Postgres·영상 PVC 포함 모든 데이터가 사라진다).
set -euo pipefail

CLUSTER="${KIND_CLUSTER_NAME:-chzzk}"
kind delete cluster --name "$CLUSTER"
