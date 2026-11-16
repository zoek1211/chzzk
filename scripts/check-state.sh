#!/usr/bin/env bash
# 스케일이 바뀌는 동안 로그인 상태·방송 목록·댓글이 유지되는지 주기적으로 확인한다.
# 1) 확인용 회원을 새로 만들고 로그인해 녹화방송(외부 영상 주소)과 댓글을 하나 만든다.
# 2) 같은 세션 쿠키로 INTERVAL초마다 로그인 전용 화면·방송 목록·댓글을 조회하고, 응답한 인스턴스와 함께 한 줄씩 출력한다.
# 사용: scripts/check-state.sh            (기본 http://localhost:8080, 5초 간격, Ctrl+C로 종료)
#       BASE=http://localhost:8080 INTERVAL=5 COUNT=60 scripts/check-state.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
INTERVAL="${INTERVAL:-5}"
COUNT="${COUNT:-0}" # 0이면 계속
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

USER_ID="check$(date +%s)"
PASSWORD="$(openssl rand -hex 12)" # 이번 실행에서만 쓰는 테스트 비밀번호(저장하지 않음)
TITLE="스케일 확인 방송 $USER_ID"
COMMENT="스케일 확인 댓글 $USER_ID"

csrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR" | tail -1; }
post() { # post <경로> <폼 인자...>
  local path="$1"; shift
  curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -b "$JAR" -c "$JAR" \
    -H "X-XSRF-TOKEN: $(csrf)" "$@" "$BASE$path"
}

curl -s -o /dev/null -b "$JAR" -c "$JAR" "$BASE/signup" # CSRF 쿠키 받기
post /signup --data-urlencode "loginId=$USER_ID" --data-urlencode "password=$PASSWORD" \
  --data-urlencode "nickname=$USER_ID" >/dev/null
curl -s -o /dev/null -b "$JAR" -c "$JAR" "$BASE/login"
post /login --data-urlencode "username=$USER_ID" --data-urlencode "password=$PASSWORD" >/dev/null

created="$(post /broadcasts --data-urlencode "title=$TITLE" \
  --data-urlencode "videoUrl=https://example.com/scale-check.mp4")"
detail="${created#* }"
detail="/${detail#*://*/}" # 리다이렉트 주소에서 경로만
post "$detail/comments" --data-urlencode "body=$COMMENT" >/dev/null
echo "확인용 회원 $USER_ID, 방송 $detail, 댓글 1개를 만들었습니다. ${INTERVAL}초마다 확인합니다."

i=0
while [[ "$COUNT" -eq 0 || "$i" -lt "$COUNT" ]]; do
  i=$((i + 1))
  login="$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/broadcasts/new")"
  list="$(curl -s -D - -b "$JAR" "$BASE/broadcasts")"
  instance="$(printf '%s' "$list" | awk -F': ' 'tolower($1) == "x-chzzk-instance" { gsub("\r", "", $2); print $2 }')"
  comments="$(curl -s -b "$JAR" "$BASE$detail/comments")"
  ok() { if "$@"; then echo 유지; else echo 실패; fi; }
  printf '%s  응답 인스턴스=%-26s 로그인=%s 방송목록=%s 댓글=%s\n' "$(date +%H:%M:%S)" "${instance:-?}" \
    "$([[ "$login" == 200 ]] && echo 유지 || echo "실패($login)")" \
    "$(ok grep -q "$TITLE" <<<"$list")" "$(ok grep -q "$COMMENT" <<<"$comments")"
  sleep "$INTERVAL"
done
