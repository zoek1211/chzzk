// k6 부하 스크립트: 공개 GET(목록, 녹화방송 상세, 댓글 조회)으로 앱 CPU를 쓴다.
// 대상 주소는 환경변수 TARGET (기본: 클러스터 안 Service 이름).
import http from 'k6/http';
import { check, sleep } from 'k6';

const TARGET = __ENV.TARGET || 'http://chzzk:8080';

export const options = {
  stages: [
    { duration: __ENV.RAMP_UP || '1m', target: Number(__ENV.VUS || 100) }, // ramp-up
    { duration: __ENV.HOLD || '4m', target: Number(__ENV.VUS || 100) },    // 유지
    { duration: __ENV.RAMP_DOWN || '30s', target: 0 },                     // ramp-down
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  const home = http.get(`${TARGET}/`, { tags: { name: 'home' } });
  check(home, { 'home 200': (r) => r.status === 200 });

  const list = http.get(`${TARGET}/broadcasts`, { tags: { name: 'broadcasts' } });
  check(list, { 'broadcasts 200': (r) => r.status === 200 });

  // 목록 HTML에서 상세 링크를 찾아 상세와 댓글을 조회한다. 방송이 없으면 목록만 호출한다.
  const ids = [...new Set([...list.body.matchAll(/\/broadcasts\/(\d+)(?!\d|\/)/g)].map((m) => m[1]))];
  if (ids.length > 0) {
    const id = ids[Math.floor(Math.random() * ids.length)];
    const detail = http.get(`${TARGET}/broadcasts/${id}`, { tags: { name: 'broadcast-detail' } });
    check(detail, { 'detail 200': (r) => r.status === 200 });
    const comments = http.get(`${TARGET}/broadcasts/${id}/comments`, { tags: { name: 'comments' } });
    check(comments, { 'comments 200': (r) => r.status === 200 });
  }
  sleep(0.1);
}
