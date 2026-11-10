# 치지직 만들기 ① 녹화방송 · ② 휴대폰 라이브 방송 · ③ 인증·인가와 댓글

| 단계 | 과제 | 태그 |
| --- | --- | --- |
| 1단계 | 치지직 만들기 ① 인증·인가 없는 녹화방송 | `step-1` |
| 2단계 | 치지직 만들기 ② 인증·인가 없는 휴대폰 라이브 송출 (1단계 기능 유지) | `step-2` |
| 3단계 | 치지직 만들기 ③ 인증·인가와 댓글 (1·2단계 기능 유지) | `step-3` |

**녹화방송(다시보기)** 을 등록·시청하고(1단계), 휴대폰 카메라·마이크로 **라이브 방송** 을 송출해 다른 화면에서 실시간으로 시청하며(2단계), **회원가입·로그인** 한 회원이 자기 방송만 관리하고 방송마다 **댓글** 로 소통하는(3단계) 서비스입니다. 방송 목록·시청·댓글 읽기는 로그인 없이 공개입니다([공개 범위](#공개-범위)).

- Java 25 이상, Spring Boot 4.1, Spring MVC + Thymeleaf, Spring Data JPA, Spring WebSocket, Spring Security(세션 폼 로그인·CSRF)
- 저장소: 파일 기반 H2 DB(회원·녹화방송·라이브 방송·댓글) + 로컬 디스크(업로드 영상)
- 라이브 영상 전달: WebRTC P2P (서버는 WebSocket 시그널링만 중계) — 선택 이유는 [ADR 0001](docs/adr/0001-webrtc-p2p-live.md)
- 라이브 권한: 송출 키 대신 로그인 세션의 소유자 검사 — 선택 이유는 [ADR 0002](docs/adr/0002-live-owner-session-replaces-broadcast-key.md)

용어는 [GLOSSARY.md](GLOSSARY.md)에 정리했습니다.

## 실행 방법

필요한 것: JDK 25 이상 (Gradle은 래퍼가 내려받습니다)

```bash
./gradlew bootRun
```

브라우저에서 <http://localhost:8080> 을 엽니다. `/broadcasts` 방송 목록 화면으로 이동합니다. 녹화방송 등록·라이브 시작·댓글 작성은 로그인이 필요하므로 먼저 헤더의 **회원가입** 으로 가입합니다(가입하면 바로 로그인됩니다).

jar로 실행하려면:

```bash
./gradlew bootJar
java -jar build/libs/chzzk-0.0.1-SNAPSHOT.jar
```

테스트:

```bash
./gradlew test
```

> 주의: 컴퓨터의 `localhost` 에서는 HTTP로도 카메라가 열리지만, **휴대폰에서는 HTTPS 주소에서만** 카메라·마이크를 쓸 수 있습니다. 아래 [휴대폰 연결 방법](#휴대폰-연결-방법)을 보세요.

## 환경변수

모두 선택 사항입니다. 예시는 [.env.example](.env.example)에 있습니다. 비밀 값(TURN 자격 증명, 키스토어 비밀번호)은 저장소에 커밋하지 말고 실행 환경에서만 설정하세요.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `PORT` | `8080` | 서버 포트 |
| `CHZZK_DATA_DIR` | `./data` | DB 파일(`chzzk.mv.db`)과 업로드 영상(`videos/`)이 저장되는 디렉터리 |
| `CHZZK_MAX_UPLOAD_SIZE` | `2GB` | 업로드 영상 최대 크기 |
| `CHZZK_VIEWER_CAPACITY` | `5` | 시청 정원: 라이브 방송 하나를 동시에 볼 수 있는 시청자 수 |
| `CHZZK_BROADCASTER_JOIN_TIMEOUT` | `30s` | 라이브를 시작한 뒤 방송자가 접속해야 하는 제한 시간. 넘기면 방송이 종료됨 |
| `CHZZK_STUN_URL` | `stun:stun.l.google.com:19302` | WebRTC STUN 서버 (공개 Google STUN) |
| `CHZZK_TURN_URL` | (없음) | TURN 서버 주소. 서로 다른 네트워크 간 연결이 필요할 때만 설정 |
| `CHZZK_TURN_USERNAME` | (없음) | TURN 사용자 이름 |
| `CHZZK_TURN_CREDENTIAL` | (없음) | TURN 자격 증명 (비밀 값) |
| `CHZZK_SSL_ENABLED` | `false` | `true` 면 HTTPS로 서비스. 기본은 HTTP |
| `CHZZK_SSL_KEY_STORE` | (없음) | 키스토어 파일 경로 (예: `file:./chzzk-keystore.p12`) |
| `CHZZK_SSL_KEY_STORE_PASSWORD` | (없음) | 키스토어 비밀번호 (비밀 값) |
| `CHZZK_SSL_KEY_STORE_TYPE` | `PKCS12` | 키스토어 종류 |

예시:

```bash
# 포트와 데이터 위치 바꾸기
PORT=9090 CHZZK_DATA_DIR=/var/lib/chzzk ./gradlew bootRun

# 시청 정원을 2명으로 줄이기 (정원 초과 확인용)
CHZZK_VIEWER_CAPACITY=2 ./gradlew bootRun

# TURN 서버 추가 (값은 본인 것으로)
CHZZK_TURN_URL=turn:turn.example.com:3478 \
CHZZK_TURN_USERNAME=<사용자> CHZZK_TURN_CREDENTIAL=<자격증명> ./gradlew bootRun

# 직접 HTTPS
CHZZK_SSL_ENABLED=true CHZZK_SSL_KEY_STORE=file:./chzzk-keystore.p12 \
CHZZK_SSL_KEY_STORE_PASSWORD=<비밀번호> ./gradlew bootRun
```

## 이번 단계 구현 범위

3단계(인증·인가와 댓글)를 추가했고, **1·2단계 기능은 모두 유지**됩니다. 단, 3단계부터 녹화방송 등록·라이브 시작에는 로그인이 필요하고 라이브의 송출 키는 로그인한 소유자 검사로 바뀌었습니다(아래 3단계 표 참고).

### 1단계: 녹화방송

| 기능 | 위치 |
| --- | --- |
| 녹화방송 등록: 제목 + **영상 파일 업로드** 또는 **이미 저장된 영상 주소(http/https)** 중 하나 | `GET /broadcasts/new` 폼, `POST /broadcasts` |
| 등록 검증: 제목 필수(200자 이하), 영상 출처는 정확히 하나, 영상 주소는 http/https만. 위반 시 400과 함께 폼에 오류 표시 | `RecordedBroadcast`, `RecordedBroadcastController` |
| 녹화방송 목록 (최신 등록순, 2단계부터는 방송 목록의 녹화방송 섹션) | `GET /broadcasts` (`/` 는 여기로 이동), `BroadcastListController` |
| 상세 화면 + HTML5 영상 플레이어 | `GET /broadcasts/{id}` |
| 업로드 영상 전송 (Range 요청 지원, 탐색 가능) | `GET /videos/{파일명}` |
| 영속 저장: 녹화방송 정보(제목, 영상 출처, 영상 위치, 등록 시각)는 H2 파일 DB에, 업로드 영상은 디스크에 저장 → 재시작 후에도 유지 | `RecordedBroadcastRepository`, `VideoStorage` |
| 로그인 없음: 모든 화면과 기능을 누구나 사용 | Spring Security 미사용 |

### 2단계: 라이브 방송

> 3단계에서 바뀐 점: 방송 시작·종료·송출은 로그인한 소유자만 할 수 있고 송출 키(`X-Broadcast-Key`, `{type:"auth"}`)는 폐지되었습니다. 표 아래 설명과 [3단계: 인증·인가와 댓글](#3단계-인증인가와-댓글)의 "송출 키 폐지"를 참고하세요.

| 기능 | 위치 |
| --- | --- |
| 방송 시작: 제목 입력 + 카메라·마이크 미리보기 후 시작. 방송자에게만 송출 키를 줌 | `GET /live/new`, `POST /live` |
| WebRTC 시그널링 (방송자는 접속 뒤 첫 메시지 `{type:"auth", key}` 로 송출 키 인증, 시청자는 누구나. 키는 주소에 싣지 않음) | `/ws/live/{id}/broadcaster`, `/ws/live/{id}/viewer` |
| 방송자 접속 유예: 시작 후 `CHZZK_BROADCASTER_JOIN_TIMEOUT`(기본 30s) 안에 방송자가 인증하지 않으면 방송이 종료됨 | `SignalingHandler` |
| 시청 화면: 방송 중이면 실시간 재생, 종료면 종료 안내와 시작·종료 시각 | `GET /live/{id}` |
| 방송 종료: 송출 키로 종료 버튼 또는 `POST /live/{id}/end` (헤더 `X-Broadcast-Key`) | 송출 화면 |
| 연결 끊김 자동 종료: 방송자 연결이 끊기면 방송이 종료되고 시청자에게 종료가 알려짐 | `SignalingHandler` |
| 시청 정원: 방송마다 따로 세며, 정원이 차면 새 시청자는 "시청자가 가득 찼습니다" 안내와 함께 거부 | `CHZZK_VIEWER_CAPACITY` |
| 카메라 전환(전면/후면), 화질 제한(최대 1280x720, 30fps) | 송출 화면 |
| 방송 목록: 라이브(방송 중 먼저, 종료는 뒤)와 녹화방송을 한 화면에 표시 | `GET /broadcasts` |
| 재시작 후 상태: 방송 중이던 라이브는 종료로 표시됨. 송출 키는 원문이 아닌 해시만 저장 | `LiveStartupCleanup` |
| STUN 기본 제공, TURN 선택 | `IceServers` |
| 선택적 HTTPS | `server.ssl.*` ← `CHZZK_SSL_*` |

이번 단계에서 하지 않는 것: 인증·인가, 라이브 녹화(종료된 라이브는 다시 볼 수 없음), 채팅·댓글, 도네이션, 녹화방송 수정·삭제.

### 로그인 없이 송출·시청하는 구성

> 3단계에서 바뀐 점: 로그인이 생겨 방송자는 "방송을 시작한 화면"이 아니라 로그인한 소유자이고, 송출 키는 없어졌습니다. 현재 구성은 [3단계: 인증·인가와 댓글](#3단계-인증인가와-댓글)과 [ADR 0002](docs/adr/0002-live-owner-session-replaces-broadcast-key.md)를 참고하세요.

로그인이 없으므로 "방송을 시작한 바로 그 화면"이 방송자입니다.

- 방송 시작 시 서버가 **송출 키**(비밀 값)를 만들어 그 화면에만 알려 줍니다. 이 키로만 그 방송을 송출·종료할 수 있고, 목록·시청 화면에는 나타나지 않습니다. 서버에는 원문이 저장되지 않습니다.
- **시청자는 누구나** 로그인 없이 방송 목록에서 라이브를 골라 봅니다.
- 송출 화면을 닫거나 새로고침해 연결이 끊기면 그 방송은 종료되고 다시 시작할 수 없습니다.

### 3단계: 인증·인가와 댓글

HTTP 경로와 상태 코드는 아래와 같습니다. 상태 변경 요청은 모두 `POST` 이고 CSRF 토큰이 필요합니다(없으면 403). 로그인이 필요한 화면·처리에 **비로그인으로 접근하면 `302` 로 `/login` 으로 보내거나(변경은 일어나지 않음) `401` 을 돌려줍니다**(둘로 나뉘는 이유는 아래). 다른 회원이 요청하면 `403`, 없는 대상은 `404` 입니다. 화면의 버튼 숨김은 편의일 뿐이고, 권한은 서버가 매 요청에서 검사합니다.

**비로그인 응답이 `302` 와 `401` 로 나뉘는 이유**

- **폼 경로(녹화방송 등록·수정·삭제, 댓글 작성·수정·삭제, `GET /live/new`)는 `302 /login`**: 브라우저가 페이지 이동으로 요청하는 경로라서 `SecurityConfig` 의 로그인 필수 규칙(`authenticated()`)에 올렸습니다. 로그인 화면으로 보냈다가 로그인하면 원래 주소로 돌려보냅니다.
- **라이브 변경 요청(`POST /live`, `POST /live/{id}/end`, `POST /live/{id}/delete`)과 방송자 WebSocket 핸드셰이크는 `401`**: 송출 화면이 `fetch` 와 WebSocket으로 부르는 경로라서, 로그인 화면(HTML)으로의 이동은 호출한 코드가 실패를 알아채기 어렵습니다. 그래서 필터 체인에서는 열어 두고, 라이브 모듈이 컨트롤러·핸드셰이크에서 로그인과 소유자를 직접 검사해 `401`(비로그인)·`403`(다른 회원)으로 답합니다. 종료된 라이브의 삭제 폼(`/live/{id}/delete`)도 같은 모듈의 규칙을 따라 `401` 입니다.

**회원**

| 기능 | HTTP 경로 | 결과 | 위치 |
| --- | --- | --- | --- |
| 회원가입 화면·처리: 아이디(영문 소문자·숫자 4~20자), 비밀번호(8자 이상), 닉네임(2~20자). 비밀번호는 BCrypt 해시로만 저장. 가입하면 바로 로그인 상태 | `GET /signup`, `POST /signup` (`loginId`, `password`, `nickname`) | 성공 `302 /`. 규칙 위반·아이디 중복 `400`과 폼 오류 | `AuthController`, `MemberService` |
| 로그인: 아이디·비밀번호가 틀리면 어느 쪽인지 알리지 않고 같은 오류 | `GET /login`, `POST /login` (`username`, `password`) | 성공 `302 /`(로그인이 필요해 이동했다면 원래 주소), 실패 `302 /login?error` | `SecurityConfig` |
| 로그아웃 | `POST /logout` | `302 /` (세션 폐기) | `SecurityConfig` |
| 인증 상태 관리: 서버 세션(`JSESSIONID` 쿠키)에 로그인 회원 저장. 헤더에 닉네임과 로그아웃 버튼(비로그인이면 로그인·회원가입) | — | — | `SecurityConfig`, `MemberDetailsService`, `CurrentMemberAdvice` |
| CSRF: `XSRF-TOKEN` 쿠키 + 폼 `_csrf` 필드 또는 `X-XSRF-TOKEN` 헤더. 시그널링 `/ws/**` 는 제외하되 방송자 접속은 세션 소유자로 검사 | 모든 `POST` | 토큰 없음 `403` | `SecurityConfig` |

**소유자 권한**

| 기능 | HTTP 경로 | 비로그인 | 다른 회원 | 소유자 |
| --- | --- | --- | --- | --- |
| 녹화방송 등록 (등록한 회원이 소유자) | `GET /broadcasts/new`, `POST /broadcasts` | `302 /login` | (회원이면 누구나 등록) | `302 /broadcasts/{id}` |
| 녹화방송 제목 수정 | `GET /broadcasts/{id}/edit`, `POST /broadcasts/{id}/edit` (`title`) | `302 /login` | `403` | `302` 상세로 (빈 제목 `400`) |
| 녹화방송 삭제 (업로드 영상 파일과 댓글도 삭제) | `POST /broadcasts/{id}/delete` | `302 /login` | `403` | `302 /broadcasts` |
| 라이브 시작 (시작한 회원이 소유자). 송출 화면은 `GET /live/new` | `POST /live` (JSON `{"title": "..."}`) | `401` (`GET /live/new` 는 `302 /login`) | (회원이면 누구나 시작) | `200 {"id": ...}`. 방송 중인 내 라이브가 이미 있으면 `409`, 제목 규칙 위반 `400` |
| 라이브 종료 (이미 종료면 그대로 `200`) | `POST /live/{id}/end` | `401` | `403` | `200` |
| 종료된 라이브 삭제 (댓글도 삭제) | `POST /live/{id}/delete` | `401` | `403` | `302 /broadcasts`. 방송 중이면 `409` |
| 방송자 WebSocket 접속 | `/ws/live/{id}/broadcaster` (핸드셰이크 검사) | `401` | `403` | 접속 허용 |

- 존재하지 않는 방송은 `404` 입니다.
- 회원 기능 이전에 만든 방송은 소유자가 없어 **누구도** 수정·삭제·종료할 수 없습니다(`403`). 보기만 됩니다.
- 녹화방송 상세·라이브 시청 화면과 방송 목록(라이브·녹화방송 카드)은 소유자 닉네임을 보여 줍니다. 소유자가 없는 기존 방송은 "소유자 없음"으로 표시됩니다.
- 수정·삭제·종료 버튼은 소유자에게만 보입니다.

**송출 키 폐지**

| 2단계 | 3단계 |
| --- | --- |
| 시작 때 송출 키 발급, 해시 저장 | 발급·저장 없음. 라이브에 소유자(회원)를 기록 |
| 방송자 WebSocket 첫 메시지 `{type:"auth", key}` | 핸드셰이크에서 세션의 로그인 회원이 소유자인지 검사. 받아들이면 서버가 `{type:"authenticated"}` 를 한 번 보냄 |
| 종료 요청 헤더 `X-Broadcast-Key` | 로그인한 소유자의 세션 쿠키 |
| 종료는 송출 화면의 키를 가진 쪽만 | 소유자는 어느 기기에서든 로그인해 종료 가능 |

접속 유예, 연결 끊김 자동 종료, 시청 정원, 재시작 시 종료 처리는 그대로입니다. **2단계 DB 파일은 그대로 쓸 수 있습니다**: 시작할 때(웹 서버가 요청을 받기 전) 옛 `live_broadcast.broadcast_key_hash` 열을 자동으로 삭제하고(`LiveStartupCleanup`), 새 열(소유자)과 `member`·`comment` 테이블은 `ddl-auto=update` 가 만듭니다. 데이터를 비울 필요가 없고, 기존 방송은 소유자 없음이 됩니다.

**댓글**

| 기능 | HTTP 경로 | 비로그인 | 다른 회원 | 작성자 |
| --- | --- | --- | --- | --- |
| 댓글 읽기: 방송별(녹화방송·라이브), 오래된 순, 작성자 닉네임·작성 시각 포함. 수정된 댓글은 `(수정됨 · HH:mm)` 으로 수정 시각을 함께 표시. 본문은 항상 이스케이프한 텍스트로 표시 | `GET /broadcasts/{id}` 와 `GET /live/{id}` 화면 하단, JSON `GET /broadcasts/{id}/comments`, `GET /live/{id}/comments` | 허용 `200` (없는 방송 `404`) | 허용 | 허용 |
| 댓글 작성 (작성자는 세션의 회원). 1~500자, 공백만이면 거절 | `POST /broadcasts/{id}/comments`, `POST /live/{id}/comments` (`body`) | `302 /login` (저장 안 됨) | (회원이면 누구나 작성) | `302` 방송 화면으로. 빈 본문·500자 초과 `400` |
| 댓글 수정 (방송 소유자여도 남의 댓글은 불가) | `POST /comments/{id}/edit` (`body`) | `302 /login` | `403` | `302` 방송 화면으로 |
| 댓글 삭제 | `POST /comments/{id}/delete` | `302 /login` | `403` | `302` 방송 화면으로 |
| 라이브 시청 화면은 댓글 JSON을 5초마다 조회해 새로고침 없이 갱신 | `GET /live/{id}/comments` | — | — | — |

- 댓글 JSON은 `[{"id", "authorNickname", "body", "createdAt", "updatedAt", "mine"}]` 입니다. `updatedAt` 은 수정한 적이 없으면 `null`, `mine` 은 요청한 회원이 쓴 댓글이면 `true`(비로그인은 항상 `false`)입니다. 회원 DB id는 JSON에도 화면(HTML)에도 싣지 않습니다. 수정·삭제 버튼은 서버가 요청마다 계산한 `mine` 으로 보여 주고, 실제 권한은 서버가 작성자 검사로 막습니다.
- 댓글은 방송별로 저장되고(종류 녹화/라이브 + 방송 id) 작성자(회원)에 연결됩니다. 방송을 삭제하면 그 방송의 댓글도 함께 삭제됩니다.
- 없는 댓글은 `404` 입니다.

이번 단계에서 하지 않는 것: 이메일 인증·비밀번호 찾기·회원 탈퇴, 소셜 로그인·JWT·관리자 역할, 방송 소유자의 남의 댓글 삭제, 댓글 답글·좋아요, 실시간 채팅(라이브 댓글은 5초 주기 조회), 도네이션, 방송 중인 라이브의 제목 수정.

### 공개 범위

시청·목록 조회는 로그인 없이 공개이고, 쓰기·관리는 서버가 로그인·소유자·작성자 여부를 검사합니다.

| 범위 | 무엇을 | 비로그인 |
| --- | --- | --- |
| **공개** (로그인 불필요) | 방송 목록 `GET /`, `GET /broadcasts`, 녹화방송 상세 `GET /broadcasts/{id}`와 영상 전송 `GET /videos/{파일명}`, 라이브 시청 `GET /live/{id}`, 시청자 시그널링 `/ws/live/{id}/viewer`, 댓글 목록(화면·JSON), 가입·로그인 화면 | 허용 |
| **회원** 전용 | 녹화방송 등록 화면·처리, 라이브 송출 화면·시작, 댓글 작성 | `302 /login` 또는 `401` |
| **소유자** 전용 | 녹화방송 수정·삭제, 라이브 종료·삭제, 방송자 시그널링 `/ws/live/{id}/broadcaster` | `302 /login` 또는 `401`, 다른 회원은 `403` |
| **작성자** 전용 | 댓글 수정·삭제 | `302 /login`, 다른 회원은 `403` (방송 소유자도 `403`) |

### 코드 구조

```
src/main/java/kr/devcourse/chzzk/
├── member/                               3단계 회원·인증
│   ├── Member.java                       회원 엔티티 (아이디 유일, 비밀번호 해시, 닉네임, 가입 시각)
│   ├── MemberService.java                가입(입력 규칙·중복 아이디 거절) 공개 인터페이스
│   ├── MemberRepository.java             JPA 저장소
│   ├── MemberDetailsService.java         Spring Security 사용자 조회 연동
│   ├── MemberPrincipal.java              세션에 담기는 로그인 회원
│   ├── AuthController.java               가입·로그인 화면, 가입 처리(가입 후 로그인 상태)
│   ├── SecurityConfig.java               세션 폼 로그인·로그아웃, 로그인 필수 경로, CSRF
│   ├── CurrentMemberAdvice.java          모든 화면에 현재 회원 제공 (헤더)
│   └── InvalidSignUpException.java
├── comment/                              3단계 댓글
│   ├── Comment.java                      댓글 엔티티 (방송 종류+id, 작성자, 본문 1~500자, 작성·수정 시각)
│   ├── BroadcastKind.java                녹화 / 라이브
│   ├── CommentKindAdvice.java            화면 템플릿이 방송 종류(BroadcastKind)를 댓글 조각에 넘기도록 모델에 제공
│   ├── CommentRepository.java            JPA 저장소
│   ├── CommentService.java               목록(오래된 순, 요청 회원의 mine 계산)·작성·수정·삭제(작성자만), 방송 삭제 시 일괄 삭제. 화면·JSON이 쓰는 CommentView 하나
│   ├── CommentController.java            댓글 작성·수정·삭제 처리
│   ├── CommentJsonController.java        댓글 목록 JSON (라이브 화면 5초 갱신용)
│   ├── CommentExceptionHandler.java      없는 방송·댓글 404, 작성자 아님 403 (화면·JSON 공통)
│   └── (예외) InvalidCommentException, BroadcastNotFoundException, CommentNotFoundException, NotCommentAuthorException
├── broadcast/                            1단계 녹화방송 (+3단계 소유자·수정·삭제)
│   ├── RecordedBroadcast.java            녹화방송 엔티티 + 등록 규칙 + 소유자
│   ├── VideoSource.java                  영상 출처 (UPLOADED / EXTERNAL)
│   ├── RecordedBroadcastRepository.java  JPA 저장소
│   ├── RecordedBroadcastService.java     등록·수정·삭제(삭제 이벤트·영상 파일 삭제)와 소유자 검사
│   ├── RecordedBroadcastController.java  등록·상세·수정·삭제 화면 (오류는 400·403·404로 변환)
│   ├── RecordedBroadcastDeleted.java     삭제 이벤트 (댓글 정리용)
│   └── (예외) InvalidRegistrationException, InvalidRenameException, RecordedBroadcastNotFoundException, NotRecordedBroadcastOwnerException
├── video/
│   ├── VideoStorage.java                 업로드 영상 디스크 보관
│   └── VideoController.java              업로드 영상 전송
├── live/                                 2단계 라이브 방송 (+3단계 소유자 권한, 송출 키 폐지)
│   ├── LiveBroadcast.java                라이브 엔티티 (제목, 상태, 시작·종료 시각, 소유자 회원)
│   ├── LiveStatus.java                   방송 중 / 종료
│   ├── LiveBroadcastRepository.java      JPA 저장소 (방송 목록 순서 쿼리 포함)
│   ├── LiveBroadcastService.java         시작(회원당 하나)·종료·삭제와 소유자 검사(findOwned)
│   ├── LiveBroadcastController.java      송출·시청 화면, 시작·종료·삭제 (401·403·404·409)
│   ├── SignalingConfig.java              WebSocket 경로 등록, 핸드셰이크에서 방송자(세션 소유자)·시청자 접속 허용 검사
│   ├── SignalingHandler.java             WebRTC 시그널링 중계, 정원, 끊김·접속 유예 초과 시 종료
│   ├── LiveStarted.java, LiveEnded.java, LiveDeleted.java  시작·종료·삭제 이벤트
│   ├── LiveStartupCleanup.java           재시작 시 방송 중 → 종료, 옛 송출 키 열 삭제 (웹 서버가 요청을 받기 전)
│   ├── IceServers.java                   STUN/TURN 설정 제공
│   └── (예외) InvalidLiveStartException, LiveConflictException, LiveNotFoundException, NotLiveOwnerException
└── list/
    └── BroadcastListController.java      방송 목록(라이브 + 녹화방송)과 첫 화면
src/main/resources/templates/             broadcasts/*(목록·상세·등록·수정), live/new.html(송출), live/watch.html(시청),
                                          member/login·signup, fragments/layout(헤더)·comments(댓글), comment/rejected
docs/adr/0001-webrtc-p2p-live.md          WebRTC P2P 선택 기록
docs/adr/0002-live-owner-session-replaces-broadcast-key.md  송출 키 폐지, 세션 소유자 검사 선택 기록
```

### 자동 테스트

`./gradlew test` 로 전체를 실행합니다.

- 1단계
  - `RecordedBroadcastWebTest`: 등록(업로드/외부 주소) → 목록 → 상세 플레이어 → 영상 전송, 잘못된 등록 거부, 없는 방송 404
  - `RestartPersistenceTest`: 재시작 후에도 녹화방송 정보와 업로드 영상이 남는지 확인
- 2단계
  - (3단계에서 바뀐 점: 송출 키 검사는 로그인한 소유자 검사로 바뀌었고, 해당 테스트는 소유자 세션으로 같은 동작을 확인합니다. 아래 3단계 목록 참고)
  - `LiveBroadcastWebTest`: 라이브 시작(송출 키 발급), 제목 검증, 방송 목록·시청 화면·송출 화면 구성, 송출 키 비노출
  - `LiveEndWebTest`: 송출 키로 종료, 틀린 키 403, 없는 방송 404, 중복 종료
  - `SignalingTest`: 송출 키 검사, 방송자·시청자 입장/퇴장 알림, offer·answer·candidate 전달 범위
  - `LiveEndSignalingTest`: 방송자 연결 끊김·종료 요청 시 시청자에게 종료 알림과 연결 종료
  - `ViewerCapacityTest`: 정원 초과 거부, 자리 비면 입장, 방송별 정원
  - `IceServersTest`: 공개 STUN 기본, TURN 설정 시 화면에 전달
  - `LiveRestartPersistenceTest`: 재시작 후 라이브 정보 유지, 방송 중 → 종료, 송출 키 원문 미저장
  - `HttpsConfigTest`: 환경변수가 없으면 HTTP가 기본
- 3단계 (권한 테스트는 비로그인 / 소유자 / 다른 회원이 같은 요청을 보내 서버 응답 코드와 결과 상태를 확인)
  - `MemberAuthWebTest`: 가입 규칙·중복 아이디 거절·가입 후 로그인 상태, 로그인 실패, 로그아웃, 비로그인 시 로그인 화면 이동, CSRF 토큰 없는 요청 403
  - `OwnerNicknameWebTest`: 시청 화면·방송 목록에 라이브·녹화방송 소유자 닉네임 표시, 소유자 없는 기존 방송은 "소유자 없음"
  - `RecordedBroadcastOwnerWebTest`: 녹화방송 등록 로그인 필수·소유자 기록, 수정·삭제 소유자만(비로그인 로그인 화면, 다른 회원 403), 삭제 시 영상 파일 제거
  - `LiveOwnerWebTest`: 라이브 시작 로그인 필수·회원당 하나(409), 종료·삭제 권한(401/403/404/409), 소유자 없는 기존 라이브는 아무도 관리 불가
  - `LegacyLiveSchemaTest`: 2단계 DB(송출 키 해시 열)를 열어도 시작 시 열이 삭제되어 새 라이브가 저장됨
  - `CommentWebTest`: 녹화방송·라이브 댓글 작성(로그인 필수, 작성자 연결), 목록 공개·오래된 순, 길이 규칙 400, 없는 방송 404
  - `CommentEditDeleteWebTest`: 댓글 수정·삭제 작성자만(비로그인 로그인 화면, 다른 회원·방송 소유자 403), 수정 시각 표시, 회원 id 비노출과 `mine`, 방송 삭제 시 댓글 삭제
  - `CommentJsonWebTest`: 댓글 목록 JSON(`updatedAt`·`mine` 포함) 공개, 없는 방송 404, 라이브 화면 5초 갱신 구성
  - `CommentRestartPersistenceTest`: 재시작 후 댓글·회원 유지
  - (보조) `TestLogin`, `MemberSessions`, `TestWeb`, `LivePages`, `SignalingClient`: 테스트용 로그인·CSRF·WebSocket 클라이언트

## 휴대폰 연결 방법

휴대폰 브라우저는 `localhost` 가 아닌 주소에서 **HTTPS가 아니면 카메라·마이크를 열지 않습니다.** 따라서 송출하는 휴대폰은 HTTPS 주소로 접속해야 합니다.

### 기본: cloudflared 터널 (권장)

계정·인증서 없이 임시 HTTPS 주소를 얻는 가장 간단한 방법입니다.

1. 컴퓨터에 [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/) 를 설치합니다 (macOS: `brew install cloudflared`).
2. 서버를 HTTP로 띄웁니다: `./gradlew bootRun`
3. 다른 터미널에서 터널을 엽니다.

   ```bash
   cloudflared tunnel --url http://localhost:8080
   ```

4. 출력에 나오는 `https://<임의이름>.trycloudflare.com` 주소를 휴대폰 브라우저에서 열고 로그인한 뒤 **송출 화면**(`/live/new`)으로 갑니다. 같은 주소의 `/live/{id}` 를 다른 화면에서 열어 시청합니다.

터널 주소는 실행할 때마다 바뀌고 터널을 끄면 사라집니다. 주소를 아는 누구나 접속할 수 있으니 시연이 끝나면 터널을 끄세요.

### 같은 Wi-Fi 권장, TURN이 필요한 경우

- 방송자와 시청자가 **같은 Wi-Fi(같은 네트워크)** 에 있으면 STUN만으로 직접 연결되어 가장 안정적이므로 시연할 때는 같은 Wi-Fi를 권장합니다. (cloudflared는 시그널링·화면만 터널링하고, 영상은 WebRTC로 직접 오갑니다.)
- 방송자와 시청자가 **서로 다른 네트워크**(예: 휴대폰은 LTE, 시청자는 회사 Wi-Fi)이거나 방화벽·통신사 NAT가 엄격해 영상이 안 뜨고 "방송 연결 중..."에 머문다면 **TURN 서버가 필요**합니다. `CHZZK_TURN_URL`, `CHZZK_TURN_USERNAME`, `CHZZK_TURN_CREDENTIAL` 을 설정하고 서버를 다시 띄웁니다(송출·시청 화면에 함께 내려갑니다).

### 선택: 직접 HTTPS (자체 서명 키스토어)

터널 없이 같은 Wi-Fi 안에서만 쓸 때의 방법입니다. 자체 서명 인증서라 브라우저 경고가 뜨고, 일부 휴대폰 브라우저는 카메라를 허용하지 않을 수 있어 **cloudflared가 더 확실**합니다.

```bash
# 1. 키스토어 만들기 (컴퓨터의 Wi-Fi IP를 SAN에 넣음. 키스토어와 비밀번호는 커밋하지 않습니다)
keytool -genkeypair -alias chzzk -keyalg RSA -keysize 2048 -validity 30 \
  -storetype PKCS12 -keystore chzzk-keystore.p12 \
  -dname "CN=chzzk" -ext "san=ip:192.168.0.10,dns:localhost"

# 2. HTTPS로 서버 실행
CHZZK_SSL_ENABLED=true \
CHZZK_SSL_KEY_STORE=file:./chzzk-keystore.p12 \
CHZZK_SSL_KEY_STORE_PASSWORD=<keytool에서 정한 비밀번호> \
./gradlew bootRun
```

`192.168.0.10` 은 본인 컴퓨터의 IP로 바꿉니다. 휴대폰에서 `https://192.168.0.10:8080/live/new` 를 열고 인증서 경고를 넘어 접속합니다. `*.p12`, `*.jks` 는 `.gitignore` 에 들어 있어 커밋되지 않습니다.

### 카메라·마이크 권한 설정

송출 화면을 처음 열면 카메라·마이크 허용을 묻습니다. **허용** 해야 방송을 시작할 수 있습니다. 실수로 거부했다면 다음과 같이 바꿉니다.

- **iOS Safari**
  1. 주소창 왼쪽 `AA` 버튼 → **웹사이트 설정** 에서 카메라·마이크를 **허용** 으로 바꿉니다.
  2. 또는 설정 앱 → Safari → 카메라 / 마이크 → 해당 사이트를 **허용** (또는 **묻기**) 으로 바꿉니다.
  3. 페이지를 새로고침합니다.
- **Android Chrome**
  1. 주소창 왼쪽 자물쇠(또는 설정) 아이콘 → **권한** → 카메라·마이크를 **허용** 으로 바꿉니다.
  2. 또는 Chrome 메뉴 → 설정 → 사이트 설정 → 카메라 / 마이크에서 해당 사이트를 허용합니다.
  3. 페이지를 새로고침합니다.
- 휴대폰 OS 설정에서 브라우저 앱 자체의 카메라·마이크 권한이 꺼져 있지 않은지도 확인합니다.
- "카메라를 사용할 수 없습니다" 류 오류가 계속되면 주소가 **https://** 인지 먼저 확인합니다.

## 직접 확인 절차

1단계·2단계 절차는 당시 그대로 두었습니다. 3단계부터 녹화방송 등록·라이브 시작에는 로그인이 필요하므로, 해당 절차 전에 헤더의 **회원가입** 으로 가입(자동 로그인)하세요. 1단계 curl 예시는 로그인 쿠키와 CSRF 토큰이 없으면 `302 /login` 또는 `403` 이 돌아오고, 로그인 포함 요청은 [3단계 curl 예시](#3단계-인증인가와-댓글-1)에 있습니다.

### 3단계: 인증·인가와 댓글

서버가 로그인·소유자·작성자를 검사하는지, 화면 버튼이 아니라 **서버 응답** 으로 확인합니다. 서로 다른 세 사람이 필요하므로 브라우저는 **일반 창(회원 A, 방송 소유자)·시크릿 창(회원 B, 다른 회원)·로그인하지 않은 창(비로그인)** 으로 나눠 씁니다. 준비: `./gradlew bootRun` 으로 서버를 띄웁니다(깨끗한 상태로 시작하려면 먼저 `data/` 를 지웁니다).

**브라우저로 확인**

1. **회원가입·로그인·로그아웃**
   - 헤더에서 **회원가입** → 아이디 `owner1`, 비밀번호 `password1`, 닉네임 `주인`을 입력합니다. 가입하면 바로 로그인되어 헤더에 닉네임과 **로그아웃** 이 보입니다.
   - 규칙 위반(아이디 `ab`, 비밀번호 7자, 닉네임 1자)으로 가입 → 오류가 나열됩니다. 같은 아이디로 다시 가입 → "이미 사용 중인 아이디입니다."
   - **로그아웃** 후 **로그인** 에서 틀린 비밀번호 → 아이디/비밀번호 중 무엇이 틀렸는지 알리지 않는 같은 오류. 맞는 값 → 로그인됩니다.
2. **비로그인**(로그아웃 상태)
   - 방송 목록, 녹화방송 상세, 라이브 시청 화면, 댓글 목록은 로그인 없이 보입니다(공개).
   - 주소창에 `/broadcasts/new` 또는 `/live/new` 를 직접 입력 → 로그인 화면으로 이동하고, 로그인하면 그 화면으로 돌아옵니다.
   - 상세·시청 화면에는 수정·삭제·종료 버튼과 댓글 작성 폼이 없습니다. (서버가 실제로 거절하는지는 아래 curl로 확인합니다. 버튼을 숨긴 것은 권한 검사가 아닙니다.)
3. **소유자(회원 A)**
   - **녹화방송 등록** 으로 방송 하나를 등록합니다(1단계 절차와 같음). 상세 화면에 소유자 닉네임 `주인` 과 **수정**·**삭제** 버튼이 보입니다. 방송 목록의 카드에도 `주인` 이 보입니다. 제목을 수정해 반영되는지 확인합니다.
   - 댓글을 작성하고(1~500자), **수정**·**삭제** 가 되는지 확인합니다. 수정한 댓글에는 `(수정됨 · HH:mm)` 이 붙습니다. 빈 댓글·501자 댓글은 거절됩니다.
   - **라이브 시작** 으로 라이브를 시작하면(2단계 절차) 시청 화면에 소유자 닉네임이 보이고 소유자만 **방송 종료** 버튼이 보이며, 종료하면 시청 화면에 **삭제** 버튼이 나타납니다. 방송 중인 채로 같은 회원으로 라이브를 하나 더 시작하면 거절(409)됩니다.
4. **다른 회원(회원 B)**: 시크릿 창에서 다른 회원(`other1`, 닉네임 `손님`)으로 가입합니다.
   - A의 녹화방송·라이브 화면에 **수정·삭제·종료 버튼이 보이지 않습니다.** 댓글은 쓸 수 있고, A의 댓글에는 수정·삭제 버튼이 없고 B 자신의 댓글에만 있습니다.
   - 라이브 시청 화면에서 A가 새 댓글을 쓰면 B 화면에 새로고침 없이 몇 초 안에 나타납니다.
5. **방송 삭제**: A가 녹화방송을 삭제하면 그 방송의 댓글도 함께 사라지고 업로드한 영상 파일도 `data/videos/` 에서 지워집니다. 종료된 라이브도 삭제하면 댓글이 함께 사라집니다. 방송 중인 라이브는 삭제 요청이 거절(409)됩니다.
6. **재시작**: 서버를 `Ctrl+C` 로 끄고 다시 띄웁니다. 회원(로그인 가능)·소유자·댓글이 그대로 남아 있습니다. 2단계 때 쓰던 `data/` 가 있어도 그대로 시작됩니다(옛 송출 키 열은 시작할 때 자동으로 지워집니다).

**curl로 확인: 버튼 숨김이 아니라 서버가 거절한다**

브라우저에서 버튼이 안 보여도 요청을 직접 보내면 서버가 막는지 확인합니다. 쿠키 파일(cookie jar)로 세션을 들고 다니며, CSRF 토큰(`XSRF-TOKEN` 쿠키 값)을 `X-XSRF-TOKEN` 헤더로 보냅니다. 서버를 `http://localhost:8080` 으로 띄운 상태에서 같은 터미널에 이어서 실행합니다. 주석에 적은 것이 기대하는 `HTTP 상태코드 이동주소` 입니다(깨끗한 `data/` 에서 시작해 방송 번호가 1이라고 가정).

```bash
BASE=http://localhost:8080
tok()  { awk '$6=="XSRF-TOKEN"{print $7}' "$1"; }                       # 쿠키 파일에서 CSRF 토큰 꺼내기
code() { curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" "$@"; } # 상태 코드만 보기
refresh() { curl -s -o /dev/null -b "$1" -c "$1" $BASE/broadcasts; }    # 로그인·가입 뒤에는 CSRF 쿠키가 지워지므로 화면을 한 번 열어 새로 받는다

# 회원 A(owner1, 소유자)와 B(other1, 다른 회원) 가입 (가입하면 로그인 상태). 비로그인용 쿠키 파일 anon.jar 도 만든다
for u in owner1 other1; do
  curl -s -o /dev/null -c $u.jar $BASE/signup
  code -b $u.jar -c $u.jar -H "X-XSRF-TOKEN: $(tok $u.jar)" \
       -d loginId=$u -d password=password1 -d nickname=${u}nick $BASE/signup   # 302 http://localhost:8080/
  refresh $u.jar
done
curl -s -o /dev/null -c anon.jar $BASE/login

# 소유자가 녹화방송(외부 영상 주소) 등록 -> 302 .../broadcasts/1
code -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" \
     -F title=hello -F videoUrl=https://example.com/v.mp4 $BASE/broadcasts
```

**녹화방송 수정·삭제**: 같은 요청을 비로그인 / 다른 회원 / 소유자로 보냅니다.

```bash
# 비로그인 -> 302 .../login (처리하지 않음, 변경 없음)
code -b anon.jar -H "X-XSRF-TOKEN: $(tok anon.jar)" -d title=hacked $BASE/broadcasts/1/edit
code -b anon.jar -H "X-XSRF-TOKEN: $(tok anon.jar)" -X POST $BASE/broadcasts/1/delete
# 다른 회원 -> 403
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -d title=hacked $BASE/broadcasts/1/edit
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -X POST $BASE/broadcasts/1/delete
# 소유자 수정 -> 302 .../broadcasts/1
code -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" -d title=renamed $BASE/broadcasts/1/edit
# CSRF 토큰 없이 -> 403
code -b owner1.jar -d title=x $BASE/broadcasts/1/edit
```

**라이브 시작·종료·삭제(송출 제어)**:

```bash
# 비로그인 시작 -> 401
code -b anon.jar -H "X-XSRF-TOKEN: $(tok anon.jar)" -H 'Content-Type: application/json' -d '{"title":"t"}' $BASE/live
# 소유자 시작 -> {"id":1}  (같은 회원이 방송 중에 또 시작하면 409)
curl -s -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" -H 'Content-Type: application/json' -d '{"title":"live"}' $BASE/live
# 비로그인 종료 -> 401, 다른 회원 종료·삭제 -> 403
code -b anon.jar   -H "X-XSRF-TOKEN: $(tok anon.jar)"   -X POST $BASE/live/1/end
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -X POST $BASE/live/1/end
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -X POST $BASE/live/1/delete
# 소유자: 방송 중 삭제 -> 409, 종료 -> 200, 종료 뒤 삭제 -> 302 .../broadcasts
code -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" -X POST $BASE/live/1/delete
code -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" -X POST $BASE/live/1/end
code -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" -X POST $BASE/live/1/delete
```

방송자 WebSocket 핸드셰이크도 서버가 검사합니다. 방송 중인 라이브(`ID`)를 소유자로 시작해 둔 상태에서 실행합니다.

```bash
ws() { curl -s -o /dev/null -m 3 -w "%{http_code}\n" -H "Connection: Upgrade" -H "Upgrade: websocket" \
       -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" "$@" $BASE/ws/live/ID/broadcaster; }
ws                    # 비로그인 -> 401
ws -b other1.jar      # 다른 회원 -> 403
ws -b owner1.jar      # 소유자 -> 101 (접속 허용. 연결이 유지되어 3초 뒤 curl이 끝나며 종료 코드 28이 나오는 것은 정상)
```

**댓글 작성·수정·삭제**:

```bash
# 비로그인 작성 -> 302 .../login (저장되지 않음)
code -b anon.jar -H "X-XSRF-TOKEN: $(tok anon.jar)" -d body=hi $BASE/broadcasts/1/comments
# 다른 회원 B 작성 -> 302 .../broadcasts/1#comments. 목록(JSON)은 로그인 없이 읽힘
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -d body=hi $BASE/broadcasts/1/comments
curl -s $BASE/broadcasts/1/comments
# 방송 소유자 A가 B의 댓글(1번) 수정·삭제 -> 403 (남의 댓글은 방송 소유자도 불가)
code -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" -d body=zz $BASE/comments/1/edit
code -b owner1.jar -H "X-XSRF-TOKEN: $(tok owner1.jar)" -X POST $BASE/comments/1/delete
# 비로그인 수정 -> 302 .../login
code -b anon.jar -H "X-XSRF-TOKEN: $(tok anon.jar)" -d body=zz $BASE/comments/1/edit
# 작성자 B: 수정 -> 302, 빈 본문 -> 400, 삭제 -> 302
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -d body=edited $BASE/comments/1/edit
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -d body= $BASE/comments/1/edit
code -b other1.jar -H "X-XSRF-TOKEN: $(tok other1.jar)" -X POST $BASE/comments/1/delete
```

비로그인 요청이 `302 /login` 이어도 데이터는 바뀌지 않습니다(이동만 시키고 처리하지 않음). 이 상태 코드들은 자동 테스트(`MemberAuthWebTest`, `RecordedBroadcastOwnerWebTest`, `LiveOwnerWebTest`, `Comment*WebTest`, `SignalingTest`)가 같은 요청으로 검증합니다.

### 2단계: 라이브 방송

> 3단계에서 바뀐 점: 송출 키가 없어져, 아래 절차는 휴대폰에서 먼저 로그인한 소유자가 시작·종료하는 것으로 바뀌었습니다(송출 키 관련 문구는 2단계 당시 기준). 로그인·권한 확인은 위의 [3단계 절차](#3단계-인증인가와-댓글-1)를 따르세요.

준비: 서버를 띄우고(`./gradlew bootRun`), 휴대폰용 HTTPS 주소를 만듭니다([휴대폰 연결 방법](#휴대폰-연결-방법)). 시청용으로 컴퓨터 브라우저(<http://localhost:8080>)를 따로 엽니다.

1. **송출 시작**: 휴대폰에서 HTTPS 주소의 `/live/new` 를 열고 카메라·마이크를 허용합니다. 미리보기가 보이면 제목을 입력하고 **방송 시작** 을 누릅니다. "방송 중입니다"와 시청 화면 링크가 나옵니다.
2. **다른 화면에서 시청**: 컴퓨터 브라우저의 방송 목록(`/broadcasts`)에서 "방송 중" 배지가 붙은 라이브를 눌러 시청 화면으로 갑니다. 몇 초 안에 휴대폰 영상이 나오는지 확인합니다. 소리가 안 나면 **음소거 해제** 를 누릅니다.
3. **카메라 전환**: 휴대폰 송출 화면의 **카메라 전환** 을 눌러 전면/후면이 바뀌는지, 시청 화면의 영상이 끊김 없이 바뀌는지 확인합니다.
4. **송출 종료 확인**: 휴대폰에서 **방송 종료** 를 누릅니다. 시청 화면에 종료 안내가 뜨고 영상이 멈추며, 방송 목록에서 해당 라이브에 "종료" 배지가 붙고 시작·종료 시각이 보이는지 확인합니다. 종료된 라이브는 다시 방송 중이 되지 않습니다.
5. **연결 끊김 자동 종료**: 새 라이브를 시작하고 시청 화면을 연 뒤, 휴대폰의 송출 화면 탭을 닫거나 Wi-Fi를 끕니다. 시청 화면에 종료가 알려지고 목록에서 "종료"가 되는지 확인합니다.
6. **시청 정원 초과**: `CHZZK_VIEWER_CAPACITY=2 ./gradlew bootRun` 으로 정원을 2로 줄여 서버를 다시 띄웁니다. 새 라이브를 시작한 뒤 시청 화면을 3개 탭에서 엽니다. 3번째 탭에는 "시청자가 가득 찼습니다"가 보이고, 앞의 두 탭 중 하나를 닫은 뒤 새로고침하면 들어와 영상이 나오는지 확인합니다. 이때 방송은 영향받지 않습니다.
7. **재시작 후 상태**: 라이브를 시작하고 방송 중인 채로 서버를 `Ctrl+C` 로 끈 뒤 다시 띄웁니다. 목록에서 그 라이브가 "종료"로 보이고 제목·시작 시각이 남아 있는지 확인합니다(방송 중 상태로 돌아가지 않음).

### 1단계: 녹화방송

1. `./gradlew bootRun` 으로 서버를 띄우고 <http://localhost:8080> 을 엽니다. 처음에는 등록된 녹화방송이 없다는 안내가 보입니다.
2. **녹화방송 등록** 을 누릅니다.
3. 제목을 입력하고 **영상 파일 업로드** 에서 mp4 파일을 고른 뒤 **등록** 을 누릅니다.
   - 상세 화면으로 이동하고, 플레이어에서 영상이 재생되는지 확인합니다. 재생 바로 원하는 위치로 이동해 봅니다.
4. 다시 **녹화방송 등록** 에서 제목과 **이미 저장된 영상 주소**(예: 공개된 mp4 주소)를 입력해 등록합니다.
   - 상세 화면에서 그 주소의 영상이 재생되는지 확인합니다.
5. 잘못된 입력을 확인합니다.
   - 영상 없이 제목만 등록 → "영상 파일을 올리거나 영상 주소를 입력해 주세요." 오류가 보이고 입력한 제목은 유지됩니다.
   - 파일과 주소를 둘 다 넣어 등록 → "하나만 입력해 주세요." 오류
   - 주소에 `ftp://...` 입력 → http/https 오류
6. 상단 로고를 눌러 목록으로 가서, 등록한 두 방송이 최신순으로 보이는지 확인하고 각각 눌러 상세로 이동합니다.
7. 서버를 `Ctrl+C`로 끄고 다시 `./gradlew bootRun` 으로 띄웁니다.
   - 목록에 두 방송이 그대로 있고, 업로드한 영상이 여전히 재생되는지 확인합니다.
8. 처음 상태로 되돌리려면 서버를 끈 뒤 `data/` 디렉터리를 지웁니다.

curl로 확인할 수도 있습니다:

```bash
curl -i -F title='테스트 다시보기' -F videoFile=@sample.mp4 http://localhost:8080/broadcasts
curl -i -F title='외부 영상' -F videoUrl=https://example.com/video.mp4 http://localhost:8080/broadcasts
```

응답의 `Location` 헤더가 새 녹화방송의 상세 화면 주소입니다.
