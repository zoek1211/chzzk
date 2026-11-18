# 치지직 만들기 ① 녹화방송 · ② 휴대폰 라이브 방송 · ③ 인증·인가와 댓글 · ④ 도네이션 · ⑤ 로컬 오토스케일

| 단계 | 과제 | 태그 |
| --- | --- | --- |
| 1단계 | 치지직 만들기 ① 인증·인가 없는 녹화방송 | `step-1` |
| 2단계 | 치지직 만들기 ② 인증·인가 없는 휴대폰 라이브 송출 (1단계 기능 유지) | `step-2` |
| 3단계 | 치지직 만들기 ③ 인증·인가와 댓글 (1·2단계 기능 유지) | `step-3` |
| 4단계 | 치지직 만들기 ④ 도네이션: 토스페이먼츠 테스트 결제 (1~3단계 기능 유지) | `step-4` |
| 5단계 | 치지직 만들기 ⑤ 로컬 오토스케일: kind 클러스터에서 여러 인스턴스와 HPA (1~4단계 기능 유지) | `step-5` |

**목차**

- [실행 방법](#실행-방법)
  - [클러스터 실행 (5단계: 여러 인스턴스와 오토스케일)](#클러스터-실행-5단계-여러-인스턴스와-오토스케일)
- [환경변수](#환경변수)
  - [토스페이먼츠 테스트 키 준비](#토스페이먼츠-테스트-키-준비)
- [단계별 구현 범위](#단계별-구현-범위)
  - [1단계: 녹화방송](#1단계-녹화방송)
  - [2단계: 라이브 방송](#2단계-라이브-방송)
  - [로그인 없이 송출·시청하는 구성](#로그인-없이-송출시청하는-구성)
  - [3단계: 인증·인가와 댓글](#3단계-인증인가와-댓글)
  - [4단계: 도네이션](#4단계-도네이션)
  - [5단계: 로컬 오토스케일](#5단계-로컬-오토스케일)
  - [공개 범위](#공개-범위)
  - [코드 구조](#코드-구조)
  - [자동 테스트](#자동-테스트)
- [휴대폰 연결 방법](#휴대폰-연결-방법)
  - [기본: cloudflared 터널 (권장)](#기본-cloudflared-터널-권장)
  - [같은 Wi-Fi 권장, TURN이 필요한 경우](#같은-wi-fi-권장-turn이-필요한-경우)
  - [선택: 직접 HTTPS (자체 서명 키스토어)](#선택-직접-https-자체-서명-키스토어)
  - [카메라·마이크 권한 설정](#카메라마이크-권한-설정)
- [직접 확인 절차](#직접-확인-절차)
  - [1단계: 녹화방송](#1단계-녹화방송-1)
  - [2단계: 라이브 방송](#2단계-라이브-방송-1)
  - [3단계: 인증·인가와 댓글](#3단계-인증인가와-댓글-1)
  - [4단계: 도네이션](#4단계-도네이션-1)
  - [5단계: 로컬 오토스케일](#5단계-로컬-오토스케일-1)

**녹화방송(다시보기)** 을 등록·시청하고(1단계), 휴대폰 카메라·마이크로 **라이브 방송** 을 송출해 다른 화면에서 실시간으로 시청하며(2단계), **회원가입·로그인** 한 회원이 자기 방송만 관리하고 방송마다 **댓글** 로 소통하고(3단계), 방송 소유자에게 토스페이먼츠 **테스트 결제** 로 **후원** 하는(4단계) 서비스입니다. 같은 서버를 로컬 Kubernetes(kind)에서 **여러 인스턴스** 로 띄우고, 부하에 따라 인스턴스 수가 자동으로 늘고 줄어도 로그인·방송·댓글·후원·라이브가 유지됩니다(5단계). 방송 목록·시청·댓글 읽기는 로그인 없이 공개입니다([공개 범위](#공개-범위)).

- Java 25 이상, Spring Boot 4.1, Spring MVC + Thymeleaf, Spring Data JPA, Spring WebSocket, Spring Security(세션 폼 로그인·CSRF)
- 저장소: 파일 기반 H2 DB(회원·녹화방송·라이브 방송·댓글·후원 주문) + 로컬 디스크(업로드 영상)
- 라이브 영상 전달: WebRTC P2P (서버는 WebSocket 시그널링만 중계) — 선택 이유는 [ADR 0001](docs/adr/0001-webrtc-p2p-live.md)
- 라이브 권한: 송출 키 대신 로그인 세션의 소유자 검사 — 선택 이유는 [ADR 0002](docs/adr/0002-live-owner-session-replaces-broadcast-key.md)
- 후원: 토스페이먼츠 결제창(테스트 키) + 서버의 승인·확인 — 선택 이유는 [ADR 0003](docs/adr/0003-toss-test-payment-with-server-confirmation.md)
- 오토스케일(5단계): Docker 이미지 + kind + Kubernetes HPA(CPU), 공용 저장소는 Postgres·Redis(세션·라이브 방)·공유 볼륨(영상) — 선택 이유는 [ADR 0004](docs/adr/0004-cluster-mode-with-shared-stores-and-redis-relay.md). 프로필 없이 실행하면 위 H2·로컬 디스크 구성 그대로입니다

용어는 [GLOSSARY.md](GLOSSARY.md)에 정리했습니다.

## 실행 방법

필요한 것: JDK 25 이상 (Gradle은 래퍼가 내려받습니다)

```bash
./gradlew bootRun
```

브라우저에서 <http://localhost:8080> 을 엽니다. `/broadcasts` 방송 목록 화면으로 이동합니다. 녹화방송 등록·라이브 시작·댓글 작성은 로그인이 필요하므로 먼저 헤더의 **회원가입** 으로 가입합니다(가입하면 바로 로그인됩니다). 후원은 토스 테스트 키가 있어야 켜집니다([토스페이먼츠 테스트 키 준비](#토스페이먼츠-테스트-키-준비)). 키가 없어도 앱은 그대로 뜨고 후원만 꺼집니다.

jar로 실행하려면:

```bash
./gradlew bootJar
java -jar build/libs/chzzk-0.0.1-SNAPSHOT.jar
```

테스트:

```bash
./gradlew test
```

### 클러스터 실행 (5단계: 여러 인스턴스와 오토스케일)

필요한 것: Docker(Docker Desktop 등, 메모리 4GB 이상 권장), [kind](https://kind.sigs.k8s.io), kubectl. JDK는 필요 없습니다(이미지 안에서 빌드).

```bash
# 1. kind 클러스터 생성 → 이미지 빌드·로드 → Secret 생성 → Postgres·Redis·앱·metrics-server·HPA 배포
scripts/cluster-up.sh

# 2. 인스턴스 수 관찰 (다른 터미널)
kubectl --context kind-chzzk get hpa,pods -w

# 3. 부하 걸기 (k6 Job, 약 5분 30초)
scripts/load.sh

# 4. 스케일이 바뀌는 동안 로그인·방송 목록·댓글이 유지되는지 확인 (다른 터미널, Ctrl+C로 종료)
scripts/check-state.sh

# 5. 정리 (클러스터 삭제)
scripts/cluster-down.sh
```

배포가 끝나면 <http://localhost:8080> 으로 접속합니다(호스트 8080 → NodePort 30080 → 앱 파드들). 컨테이너 이미지만 만들려면 `docker build -t chzzk:local .` 입니다. 이미지를 프로필 없이 실행(`docker run -p 8080:8080 chzzk:local`)하면 단독 모드로 뜹니다. 확인 절차와 실제 결과는 [5단계 직접 확인 절차](#5단계-로컬-오토스케일-1)에 있습니다.

> 주의: 컴퓨터의 `localhost` 에서는 HTTP로도 카메라가 열리지만, **휴대폰에서는 HTTPS 주소에서만** 카메라·마이크를 쓸 수 있습니다. 아래 [휴대폰 연결 방법](#휴대폰-연결-방법)을 보세요.

## 환경변수

모두 선택 사항입니다. 예시는 [.env.example](.env.example)에 있습니다. 비밀 값(TURN 자격 증명, 키스토어 비밀번호, 토스 시크릿 키)은 저장소에 커밋하지 말고 실행 환경에서만 설정하세요.

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
| `TOSS_CLIENT_KEY` | (없음) | 토스페이먼츠 클라이언트 키(`test_ck_...`, 결제창을 여는 공개 값). 시크릿 키와 **둘 다** 있어야 후원이 켜짐 |
| `TOSS_SECRET_KEY` | (없음) | 토스페이먼츠 시크릿 키(`test_sk_...`, 비밀 값. 서버만 사용하고 화면·로그에 내지 않음) |
| `TOSS_CONNECT_TIMEOUT` | `5s` | 토스 API 연결 제한 시간 |
| `TOSS_READ_TIMEOUT` | `10s` | 토스 API 응답 읽기 제한 시간. 넘으면 일시 오류로 보고 결제를 조회해 다시 확인함 |

클러스터 모드(5단계) 전용 변수입니다. `SPRING_PROFILES_ACTIVE=cluster` 일 때만 읽고, k8s 매니페스트([k8s/chzzk.yaml](k8s/chzzk.yaml))가 설정합니다. 직접 정할 필요는 없습니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | (없음) | `cluster` 면 클러스터 모드(Postgres·Redis·공유 볼륨). 없으면 단독 모드(H2·메모리) |
| `CHZZK_DB_URL` | `jdbc:postgresql://localhost:5432/chzzk` | Postgres 주소 (클러스터에서는 `jdbc:postgresql://postgres:5432/chzzk`) |
| `CHZZK_DB_USERNAME` | `chzzk` | Postgres 사용자 |
| `CHZZK_DB_PASSWORD` | (없음) | Postgres 비밀번호 (비밀 값. `scripts/cluster-up.sh` 가 무작위로 만들어 k8s Secret `chzzk-secrets` 에만 둠) |
| `CHZZK_REDIS_HOST` | `redis` | Redis 호스트 (로그인 세션, 라이브 방 상태, 시그널링 중계) |
| `CHZZK_REDIS_PORT` | `6379` | Redis 포트 |
| `CHZZK_REDIS_PREFIX` | `chzzk:` | Redis 키·채널 접두어 (테스트 격리용) |
| `CHZZK_INSTANCE_ID` | 파드 이름(`HOSTNAME`) | 인스턴스 이름. 시그널링 중계와 응답 헤더 `X-Chzzk-Instance` 에 쓰임 |
| `CHZZK_DATA_DIR` | `/data` (이미지 기본값) | 모든 파드가 마운트하는 공유 볼륨. 업로드 영상은 `/data/videos` |

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

# 후원(토스 테스트 결제) 켜기 (값은 본인 테스트 키로)
TOSS_CLIENT_KEY=test_ck_<클라이언트키> TOSS_SECRET_KEY=test_sk_<시크릿키> ./gradlew bootRun

# 클러스터에 토스 테스트 키 넣기 (Secret 에만 저장되고, 다시 실행할 때 비워 두면 기존 값을 유지)
TOSS_CLIENT_KEY=test_ck_<클라이언트키> TOSS_SECRET_KEY=test_sk_<시크릿키> scripts/cluster-up.sh
```

### 토스페이먼츠 테스트 키 준비

실제 돈이 오가지 않는 **테스트 결제** 만 씁니다.

1. [토스페이먼츠 개발자센터](https://developers.tosspayments.com)에 가입·로그인하고 내 개발 정보(API 키)에서 **API 개별 연동 키** 의 테스트 키를 확인합니다. 클라이언트 키는 `test_ck_` 로, 시크릿 키는 `test_sk_` 로 시작합니다.
2. 두 값을 위 환경변수 `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY` 로 설정해 서버를 띄웁니다. 시크릿 키는 저장소에 커밋하지 마세요.
3. 키가 하나라도 없으면 **후원이 꺼진 채** 앱이 뜹니다. 방송 화면의 후원 영역에는 안내 문구와 합계·내역만 보이고, `POST /donations` 는 `503` 입니다. 다른 기능에는 영향이 없습니다.
4. 웹훅(선택)은 결제사가 이 서버로 직접 호출해야 하므로 공개 주소가 필요합니다. `cloudflared tunnel --url http://localhost:8080` 으로 임시 주소를 만들고([휴대폰 연결 방법](#휴대폰-연결-방법)과 같은 방법), 개발자센터의 웹훅 설정에 `https://<임의이름>.trycloudflare.com/donations/webhook` 을 등록해 결제 상태 변경 이벤트를 받습니다. 등록하지 않아도 성공 리다이렉트만으로 후원은 확정됩니다.

## 단계별 구현 범위

1단계부터 5단계까지 단계마다 추가한 기능을 소단원으로 정리했습니다. 각 단계는 앞 단계 기능을 모두 유지합니다. 단, 3단계부터 녹화방송 등록·라이브 시작에는 로그인이 필요하고 라이브의 송출 키는 로그인한 소유자 검사로 바뀌었습니다(아래 3단계 표 참고). 5단계의 클러스터 모드는 프로필을 켰을 때만 동작하고, 기본 실행(단독 모드)은 1~4단계 그대로입니다.

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
> 5단계에서 바뀐 점: 클러스터 모드에서는 방송자 연결이 끊겨도 바로 종료하지 않고 접속 유예 시간(`CHZZK_BROADCASTER_JOIN_TIMEOUT`) 안에 다시 접속하지 않을 때 종료하며, 서버가 시작될 때 라이브를 모두 종료하지도 않습니다. 방송자·시청자 화면은 연결이 끊기면 자동으로 다시 접속합니다. 단독 모드는 아래 그대로입니다. [5단계: 로컬 오토스케일](#5단계-로컬-오토스케일)을 보세요.

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

> 4단계에서 바뀐 점: 아래 "이번 단계에서 하지 않는 것"의 도네이션은 4단계에서 추가했습니다. [4단계: 도네이션](#4단계-도네이션)을 보세요.

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

### 4단계: 도네이션

후원은 **후원 주문 → 결제창 → 서버 승인·확인 → 확정** 순서로 이뤄집니다. 화면에 "성공"이 보여도 서버가 토스에 승인을 확인하기 전에는 확정되지 않습니다. 용어는 [GLOSSARY.md](GLOSSARY.md)(후원, 후원 주문, 승인 통지, 수신자)를 따르고, 선택 이유는 [ADR 0003](docs/adr/0003-toss-test-payment-with-server-confirmation.md)입니다. 방송 종류 값은 녹화방송 `RECORDED`, 라이브 `LIVE` 입니다.

| 범위 | 경로 | 누가 | 응답 |
| --- | --- | --- | --- |
| **후원 대상·금액 선택**: 녹화방송 상세·라이브 시청 화면의 후원 영역에서 금액 버튼(1,000·5,000·10,000원)이나 입력(1,000원 단위, 1,000~1,000,000원)과 메시지(선택, 100자 이하)를 고름 | `GET /broadcasts/{id}`, `GET /live/{id}` 하단 | 로그인한 회원(자기 방송 제외). 비로그인은 로그인 링크, 키 없음은 안내만 | `200` |
| **테스트 결제 요청**: 서버가 후원 주문(`대기`)을 만들고 수신자(방송 소유자)·금액을 고정한 뒤, 브라우저가 그 주문 번호·금액으로 토스 결제창을 엶 | `POST /donations` (JSON `{"kind", "broadcastId", "amount", "message"}`, CSRF 토큰 필요) | 로그인한 회원 | 성공 `201 {"orderId", "amount", "orderName", "clientKey", "successUrl", "failUrl"}`. 비로그인 `401`, 없는 방송 `404`, 금액·메시지·자기 방송·소유자 없는 방송·방송 미선택 `400 {"message"}`, CSRF 없음 `403`, 키 없음 `503 {"message"}` |
| **서버 승인·금액·수신자 검증 후 확정**: 결제창이 성공으로 돌아오는 주소. 서버가 쿼리 금액이 주문 금액과 같은지 보고 토스 승인 API를 호출한 뒤, 응답(상태 `DONE`·금액·주문 번호)과 방송·수신자가 맞을 때만 `대기 → 확정`. **이 주문의 결제**가 검증에 실패하면 토스에 취소를 요청하고, 다른 주문의 결제(주문 번호가 다름)나 이미 다른 후원에 쓰인 결제 키는 취소하지 않음. 승인 호출이 네트워크 오류·시간 초과·"이미 처리됨" 등으로 실패하면 조회 API로 다시 확인: `DONE` 이면 그대로 검증·확정, 끝난 비완료 상태(중단·만료·취소)나 확정적인 카드 거절 코드면 `실패`, 조회도 실패하거나 아직 진행 중이면 `대기` 로 두고 웹훅·다시 열기로 확정 | `GET /donations/success?paymentKey=&orderId=&amount=` | 로그인한 **후원자 본인**만. 비로그인은 로그인 화면으로 `302`(로그인 뒤 이 주소로 돌아옴) | 확정 `200` 결과 화면(방송으로 돌아가는 링크). 금액·응답 불일치·검증 실패·결제 실패 `400` 실패 화면(주문 `실패`), 결제 확인이 지연돼 `대기` 로 남으면 `202` 에 "결제 확인이 지연되고 있으며 결제가 확인되면 후원이 반영됩니다.", 후원자가 아님 `403`(주문 정보 없이 한 문장), 없는 주문 `404`, 필수 값 누락·형식 오류 `400`. 화면 경로의 `403`·`404`·`400` 은 모두 한국어 한 문장을 보임 |
| **결제 실패·취소**: 결제창이 실패로 돌아오는 주소. 코드 `PAY_PROCESS_CANCELED` 면 `취소`, 그 밖은 `실패`. `대기` 주문만 바뀌고 이미 끝난 주문은 그대로(확정은 유지) | `GET /donations/fail?code=&message=&orderId=` | 로그인한 **후원자 본인**만(비로그인은 로그인 화면으로 `302`) | `200` 취소·실패 결과 화면(다시 시도 링크, 취소가 아닌 실패는 토스가 준 사유도 함께 보임). 후원자가 아님 `403`(주문 정보 없음), 없는 주문 `404`, `orderId` 없음 `400` |
| **승인 통지(웹훅)**: 토스가 보내는 승인 통지. 본문의 `data.paymentKey`, `data.orderId` 만 읽고 **본문을 믿지 않고** 토스 조회 API로 다시 확인해 같은 확정 로직을 탐. 모르는 주문·미완료·불일치는 아무것도 바꾸지 않음(웹훅으로는 주문을 `실패`로 만들지 않아 이후 통지로 확정될 수 있고, **웹훅은 결제를 취소하지도 않음**). 호출 횟수 제한은 없음(알려진 한계: 조회로 다시 확인하므로 위조 본문으로는 확정되지 않지만 토스 조회 호출을 늘릴 수는 있음) | `POST /donations/webhook` (JSON, 로그인·CSRF 토큰 불필요) | 결제사 | 항상 `200` (본문 오류 포함) |
| **중복 확정 방지**: 새로고침·웹훅 재전송·두 경로가 겹쳐도 한 번만 확정. `대기 → 확정` 은 `대기` 일 때만 바꾸는 조건부 갱신으로 한 번만 일어나고, `orderId`·`paymentKey` 에 유니크 제약. 이미 확정된 주문의 통지는 같은 결과를 돌려주고 아무것도 바꾸지 않음 | 위 두 경로 | — | 합계 불변 |
| **방송별 후원 내역·합계**: 확정된 후원만 최신 순으로(후원자 닉네임·금액·메시지·시각), 합계는 확정 금액의 합. 대기·실패·취소 주문은 제외. 메시지는 항상 이스케이프된 텍스트이고 회원 DB id는 싣지 않음 | 녹화방송 상세, 라이브 시청 화면 | 누구나(공개) | `200` |

- 결제창이 페이지를 떠났다 돌아오므로, 라이브 시청 중 후원하면 결과 화면의 링크로 시청 화면에 돌아옵니다.
- **수신자는 서버가 방송 소유자로 정합니다.** 클라이언트가 보낸 수신자·금액은 신뢰하지 않습니다.
- 결제를 끝내지 않은 `대기` 주문은 정리하지 않고 남지만 내역·합계에는 들어가지 않습니다. 방송이 삭제되면 후원 기록은 남지만 화면에는 더 보이지 않습니다.
- 토스 호출에는 연결 5초·읽기 10초의 제한 시간이 있고(`TOSS_CONNECT_TIMEOUT`, `TOSS_READ_TIMEOUT`), 넘으면 일시 오류로 다룹니다. 결제 키는 로그에 앞 4자만 남깁니다.
- 토스에 넘기는 `successUrl`·`failUrl` 은 요청 주소로 만들고, `server.forward-headers-strategy=framework` 로 cloudflared 같은 터널의 `X-Forwarded-*` 헤더를 반영해 공개 `https` 주소가 됩니다.
- 이번 단계에서 하지 않는 것: 실제 결제(테스트 키만 사용), 환불·정산, 웹훅 호출 횟수 제한.

**코드 구조 (`kr.devcourse.chzzk.donation`)**

```
DonationService.java            공개 인터페이스: 주문 생성(createOrder), 승인 통지 확정(confirmRedirect: 후원자 본인의 성공 리다이렉트, confirmWebhook), 실패·취소 반영(recordFailure), 방송별 내역·합계(list, total), 후원 가능 여부(canDonate). 방송 소유자는 방송 모듈(ownerIdOf)에게 물음
DonationOrder.java              후원 주문 엔티티 (주문 번호·결제 키 유니크, 방송 종류+id, 후원자, 수신자, 금액, 메시지, 상태)
DonationOrderRepository.java    조건부 갱신(confirmIfPending·failIfPending·cancelIfPending)과 합계·내역 쿼리
DonationStatus.java, DonationKind.java   대기/확정/실패/취소, 녹화/라이브
DonationJsonController.java     POST /donations (주문 생성)
DonationController.java         GET /donations/success, /donations/fail (결과 화면)
DonationWebhookController.java  POST /donations/webhook
DonationExceptionHandler.java   JSON 경로의 오류 응답 ({"message"})
DonationPageExceptionHandler.java  화면 경로의 오류 응답 (결과 화면에 한국어 한 문장: 400·403·404, 지연 202)
ApprovalNotice.java             승인 통지 경로 구분 (REDIRECT, WEBHOOK)
PaymentGateway.java             결제사 경계(승인 confirm·조회 query·취소 cancel). 서비스는 이 인터페이스만 안다
TossPaymentGateway.java         토스 API 호출 구현 (시크릿 키는 여기서만 사용)
UnavailablePaymentGateway.java  키가 없을 때의 자리표시 구현
DonationAvailability.java       키 두 개가 모두 있을 때만 후원 사용. 클라이언트 키만 화면에 냄
DonationConfig.java             키 유무에 따라 게이트웨이 빈 선택 (테스트는 다른 구현으로 대체)
templates/fragments/donations.html  후원 영역(폼·합계·내역·결제창 호출), templates/donations/result.html  결과 화면
```

보안 설정(`SecurityConfig`)은 `/donations/success`·`/donations/fail` 에 로그인을 요구하고(비로그인은 `302` 로 로그인 화면), `/donations/webhook` 만 CSRF에서 제외하고, `POST /donations` 는 CSRF 토큰이 필요합니다. `POST /donations` 의 로그인 검사는 컨트롤러가 직접 해 `401` 로 답합니다(`fetch` 호출이라 로그인 화면 이동 대신 상태 코드가 필요).

**자동 테스트** (결제사는 가짜 `FakePaymentGateway` 로 바꿔 네트워크 없이 확인합니다. **실제 토스 서버로는 호출하지 않았습니다**)

- `DonationWebTest`: 주문 생성 `201`·`401`·`404`·`400`·CSRF 거절, 성공 리다이렉트 확정과 방송 화면의 합계·내역·이스케이프, 라이브 화면 합계, 확정 전 합계 제외, 금액을 바꾼 주소 거절, 승인 뒤 응답 불일치 시 취소, 없는 주문 `404`
- `DonationFailWebTest`: 구매자 취소(`취소` 화면·다시 시도 링크)와 그 밖의 실패(`실패` 화면), 이미 닫힌 주문은 바뀌지 않음, 이미 확정된 주문에 실패가 와도 확정 유지, 없는 주문·`orderId` 없음
- `DonationWebhookTest`: 웹훅만으로 확정, 같은 웹훅 반복·웹훅과 성공 리다이렉트 순서 조합·성공 리다이렉트 반복·동시 요청에서 한 번만 확정, `DONE` 아님·모르는 주문·본문 오류·금액 불일치는 `200` 에 변경 없음, 승인 API가 "이미 처리됨"이면 조회로 확인
- `DonationDisabledWebTest`: 키가 없으면 `POST /donations` `503` 과 한국어 메시지, 방송 화면은 폼 대신 안내와 합계, 비밀 키 비노출
- `DonationSafetyWebTest`: 남의 결제 키로 성공 주소·웹훅을 호출해도 그 결제를 취소하지 않음, 웹훅은 금액이 달라도 취소하지 않음, 이미 쓰인 결제 키는 취소하지 않음, 승인 호출 실패 뒤 조회로 확정·지연 `202`·끝난 상태 `실패`·카드 거절, 비로그인 `302`·다른 회원 `403`(주문 정보 없음), 실패 사유 이스케이프, 화면 경로의 한국어 오류 문장, 로그에 결제 키 전체가 없음
- `TossPaymentGatewayTest`: 토스 승인·조회·취소 요청의 주소·인증·본문, 오류 응답·네트워크 오류·읽기 시간 초과 변환
- `DonationRestartPersistenceTest`: 재시작 후에도 확정된 후원·합계가 남고 대기 주문은 합계에 들어가지 않음

### 5단계: 로컬 오토스케일

서버를 컨테이너 이미지로 만들어 로컬 Kubernetes(kind)에 **여러 인스턴스** 로 띄우고, CPU 사용률을 보고 HPA가 인스턴스 수를 2~5개 사이에서 자동으로 늘리고 줄입니다. 인스턴스는 언제든 생기고 사라지므로, 사용자가 다시 봐야 하는 상태는 모두 인스턴스 밖 **공용 저장소** 에 둡니다(`cluster` 프로필 = **클러스터 모드**). 프로필 없이 실행하면(단독 모드) 1~4단계와 똑같이 H2·메모리로 혼자 동작합니다. 용어는 [GLOSSARY.md](GLOSSARY.md)(인스턴스, 공용 저장소, 클러스터 모드, 오토스케일, 방송자 접속 기록, 시그널링 중계), 선택 이유는 [ADR 0004](docs/adr/0004-cluster-mode-with-shared-stores-and-redis-relay.md)입니다. 클라우드 배포는 하지 않습니다.

| 범위 | 위치 | 내용 |
| --- | --- | --- |
| **컨테이너 이미지 빌드** | [Dockerfile](Dockerfile), [.dockerignore](.dockerignore) | 멀티 스테이지: JDK 25로 `bootJar` → JRE 25로 실행. 비루트 사용자, `/data` 볼륨, 8080 포트 |
| **여러 인스턴스 배포** | [k8s/chzzk.yaml](k8s/chzzk.yaml) (Deployment), [k8s/kind-config.yaml](k8s/kind-config.yaml), [scripts/cluster-up.sh](scripts/cluster-up.sh) | kind 단일 노드 클러스터에 앱 Deployment(평소 2개). CPU 요청 250m·상한 2, 메모리 512Mi~1Gi, `/actuator/health/liveness`·`readiness` 프로브, 종료 전 5초 대기(`preStop`)와 60초 종료 유예 |
| **지표 기반 자동 조절 (최소·최대)** | [k8s/chzzk.yaml](k8s/chzzk.yaml) (HorizontalPodAutoscaler), [k8s/metrics-server.yaml](k8s/metrics-server.yaml) | `autoscaling/v2` HPA: **CPU 평균 사용률 50%** 기준, **최소 2 · 최대 5**, 축소 안정화 60초(부하가 멈추고 약 1분 뒤부터 줄어듦). 지표는 metrics-server(kind용 `--kubelet-insecure-tls`) |
| **요청 분산** | [k8s/chzzk.yaml](k8s/chzzk.yaml) (Service `chzzk`, NodePort 30080) | 모든 앱 파드 앞의 Service가 HTTP와 WebSocket 요청을 나눠 보냄. 호스트 `localhost:8080` → NodePort 30080. 클러스터 모드 응답에는 처리한 인스턴스 이름이 `X-Chzzk-Instance` 헤더로 붙음(`InstanceHeaderFilter`) |
| **공용 저장소: 회원·방송·댓글·후원** | [k8s/postgres.yaml](k8s/postgres.yaml), `application-cluster.properties` | Postgres 1개(PVC). 같은 JPA 엔티티를 그대로 씀 |
| **공용 저장소: 로그인 세션** | `application-cluster.properties`, `build.gradle` (Spring Session Data Redis) | HTTP 세션을 Redis에 둬 어느 인스턴스로 가도 로그인이 유지됨. 단독 모드에서는 Redis 자동 설정을 끔(`application.properties` 의 `spring.autoconfigure.exclude`) |
| **공용 저장소: 업로드 영상** | [k8s/chzzk.yaml](k8s/chzzk.yaml) (PVC `chzzk-data` → `/data`) | 모든 앱 파드가 같은 볼륨을 마운트. 영상 저장 코드는 그대로 |
| **공용 저장소: 라이브 상태** | `live/RedisLiveRooms.java`, [k8s/redis.yaml](k8s/redis.yaml) | 방송 정보는 Postgres, 방 상태(방송자 접속 기록, 시청자 명단·정원)는 Redis, 다른 인스턴스에 붙은 상대에게는 Redis pub/sub으로 시그널링 메시지와 종료 알림을 전달. 아래 설명 참고 |
| **부하 발생** | [load/k6-load.js](load/k6-load.js), [k8s/k6-job.yaml](k8s/k6-job.yaml), [scripts/load.sh](scripts/load.sh) | k6(`grafana/k6` 이미지)를 클러스터 안 Job으로 실행. 가상 사용자 100명, 1분 증가 → 4분 유지 → 30초 감소. 홈·방송 목록·녹화방송 상세·댓글 조회 |
| **스케일 중 상태 유지 확인** | [scripts/check-state.sh](scripts/check-state.sh) | 확인용 회원으로 로그인해 방송·댓글을 만들고, 같은 세션으로 주기적으로 로그인 전용 화면·방송 목록·댓글을 조회해 응답 인스턴스와 함께 출력 |
| **비밀 값** | [scripts/cluster-up.sh](scripts/cluster-up.sh) | DB 비밀번호는 배포할 때 `openssl rand` 로 만들어 Secret `chzzk-secrets` 에만 둠. 토스 키는 환경변수로 줄 때만 Secret에 넣음. 저장소에는 비밀 값이 없음 |

**라이브가 여러 인스턴스에서 동작하는 방식 (클러스터 모드)**

- 영상은 WebRTC P2P라 서버를 거치지 않습니다. 서버가 중계하는 것은 시그널링(offer·answer·candidate)뿐이므로, 방송자와 시청자가 서로 다른 인스턴스에 붙어도 Redis pub/sub으로 메시지를 전달하면 됩니다. 메시지 형식은 2단계와 같습니다.
- **방송자 접속 기록**: 방송자 연결을 가진 인스턴스가 Redis 키(만료 6초)를 주기적으로 갱신합니다. 다른 인스턴스에 이미 방송자가 있으면 새 방송자 연결은 닫힙니다.
- **시청 정원**: 시청자 명단(시청자 → 인스턴스)을 Redis에 두고 Lua 스크립트로 원자적으로 검사합니다. 살아 있지 않은 인스턴스의 시청자는 정원에서 빠집니다.
- **종료 판정**: 방송자 연결이 끊겨도 바로 종료하지 않습니다. 방송자 접속 기록이 접속 유예 시간(기본 30초)보다 오래 없는 라이브만, Redis 시각 기준으로 한 인스턴스가 원자적으로 판정해 종료합니다. 종료는 방송 중인 라이브에만 한 번 일어나고(조건부 갱신), 종료 알림은 모든 인스턴스로 퍼져 각자 자기 연결에 `ended` 를 보냅니다.
- **인스턴스가 사라질 때**: 정상 종료하는 인스턴스는 자기 연결을 닫기 코드 `1012`(재시작)로 닫고 방송은 종료하지 않습니다. 방송자·시청자 화면은 `ended` 없이 끊기면 `GET /live/{id}/status`(`{"onAir": true|false}`)로 방송 여부를 확인한 뒤 백오프로 다시 접속합니다(최대 20번). 방송자 화면은 이미 연결된 시청자 피어를 유지하고, 시청자 화면은 새로 협상합니다(잠깐 끊길 수 있음).
- 새 인스턴스가 시작돼도 진행 중인 라이브를 종료하지 않습니다(단독 모드는 2단계처럼 시작 시 모두 종료).

**알려진 한계**: 공유 볼륨은 kind 단일 노드의 `ReadWriteOnce` 볼륨이라 노드가 여러 개인 실제 클러스터에서는 `ReadWriteMany` 스토리지나 오브젝트 스토리지가 필요합니다. Postgres·Redis는 한 개씩이라 이중화하지 않습니다. 스키마는 `ddl-auto=update` 로 맞추므로 처음 배포 때는 `cluster-up.sh` 가 DB 준비를 기다린 뒤 앱을 띄웁니다.

**코드 구조 (5단계 추가·변경)**

```
live/LiveRooms.java             라이브 방 경계: 방송자·시청자 입장, 메시지 전달, 종료 알림. SignalingHandler는 이 인터페이스와 자기 WebSocket 연결만 안다
live/MemoryLiveRooms.java       단독 모드 구현 (2~4단계 동작 그대로)
live/RedisLiveRooms.java        클러스터 모드 구현 (방송자 접속 기록, 시청자 명단·정원 Lua, pub/sub 중계, 주기 종료 판정, 정상 종료 시 1012)
live/LiveBroadcastController.java  GET /live/{id}/status 추가 (화면 재접속 판단용, 공개)
live/LiveStartupCleanup.java    방이 인스턴스보다 오래 사는 구현(클러스터)이면 시작 시 전체 종료를 하지 않음
InstanceHeaderFilter.java       클러스터 모드 응답에 X-Chzzk-Instance 헤더
application-cluster.properties  Postgres·Redis·세션·인스턴스 이름 설정
templates/live/new.html, watch.html  연결이 끊기면 자동 재접속
```

**자동 테스트** (Testcontainers로 Postgres·Redis를 띄우고 서버 인스턴스 2~3개를 같은 저장소로 올립니다. Docker가 없으면 이 테스트만 건너뜁니다. CI(GitHub Actions)에서는 돌아갑니다)

- `ClusterModeTest`: A에서 로그인한 세션으로 B에서 로그인 전용 화면, A에서 만든 방송·댓글이 B에 보이고 A로 올린 영상을 B에서 구간 재생, 라이브 중 새 인스턴스가 떠도 방송 유지, 응답마다 인스턴스 이름 헤더
- `ClusterLiveTest`: 방송자 A·시청자 B 사이 offer·answer·candidate 전달, 늦게 붙은 방송자가 다른 인스턴스의 시청자를 받음, 다른 인스턴스의 중복 방송자 거절, A·B에 걸친 정원 초과 `full`, A에서 종료하면 B의 시청자가 `ended`, 시청자 인스턴스를 닫아도 방송 유지·재접속 코드, 방송자 인스턴스를 닫고 유예 안에 다른 인스턴스로 재접속하면 유지, 유예를 넘기면 종료, 접속 기록 키가 만료돼도 연결이 있으면 되살림, 중복 방송자 정리
- `ActuatorHealthTest`: `/actuator/health`(liveness·readiness)만 공개, 단독 모드에는 인스턴스 헤더 없음
- `LiveEndWebTest`: `GET /live/{id}/status` (방송 중·종료·없음 `404`)
- 단독 모드의 1~4단계 테스트는 모두 그대로 통과합니다(라이브 방 경계를 옮긴 리팩터링의 안전망)

### 공개 범위

시청·목록 조회는 로그인 없이 공개이고, 쓰기·관리는 서버가 로그인·소유자·작성자 여부를 검사합니다. (4단계: 방송별 후원 합계·내역은 공개이고, 후원 주문 생성은 로그인한 회원만 합니다. 승인 확인 경로 `GET /donations/success`·`fail` 과 웹훅은 로그인이 아니라 주문과 결제사 확인으로 판단합니다. 자세한 것은 4단계 표.)

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
- 4단계: 위 [4단계: 도네이션](#4단계-도네이션)의 "자동 테스트" 목록 (`Donation*Test`, `TossPaymentGatewayTest`, 보조 `FakePaymentGateway`)
- 5단계: 위 [5단계: 로컬 오토스케일](#5단계-로컬-오토스케일)의 "자동 테스트" 목록 (`ClusterModeTest`, `ClusterLiveTest`, `ActuatorHealthTest`, 보조 `ClusterHarness`)

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

1단계부터 순서대로 정리했습니다. 1단계·2단계 절차는 당시 그대로 두었습니다. 5단계(로컬 오토스케일)는 Docker·kind·kubectl이, 4단계(도네이션)는 토스 테스트 키가 필요합니다. 3단계부터 녹화방송 등록·라이브 시작에는 로그인이 필요하므로, 해당 절차 전에 헤더의 **회원가입** 으로 가입(자동 로그인)하세요. 1단계 curl 예시는 로그인 쿠키와 CSRF 토큰이 없으면 `302 /login` 또는 `403` 이 돌아오고, 로그인 포함 요청은 [3단계 curl 예시](#3단계-인증인가와-댓글-1)에 있습니다.

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

### 2단계: 라이브 방송

> 3단계에서 바뀐 점: 송출 키가 없어져, 아래 절차는 휴대폰에서 먼저 로그인한 소유자가 시작·종료하는 것으로 바뀌었습니다(송출 키 관련 문구는 2단계 당시 기준). 로그인·권한 확인은 아래 [3단계 절차](#3단계-인증인가와-댓글-1)를 따르세요.

준비: 서버를 띄우고(`./gradlew bootRun`), 휴대폰용 HTTPS 주소를 만듭니다([휴대폰 연결 방법](#휴대폰-연결-방법)). 시청용으로 컴퓨터 브라우저(<http://localhost:8080>)를 따로 엽니다.

1. **송출 시작**: 휴대폰에서 HTTPS 주소의 `/live/new` 를 열고 카메라·마이크를 허용합니다. 미리보기가 보이면 제목을 입력하고 **방송 시작** 을 누릅니다. "방송 중입니다"와 시청 화면 링크가 나옵니다.
2. **다른 화면에서 시청**: 컴퓨터 브라우저의 방송 목록(`/broadcasts`)에서 "방송 중" 배지가 붙은 라이브를 눌러 시청 화면으로 갑니다. 몇 초 안에 휴대폰 영상이 나오는지 확인합니다. 소리가 안 나면 **음소거 해제** 를 누릅니다.
3. **카메라 전환**: 휴대폰 송출 화면의 **카메라 전환** 을 눌러 전면/후면이 바뀌는지, 시청 화면의 영상이 끊김 없이 바뀌는지 확인합니다.
4. **송출 종료 확인**: 휴대폰에서 **방송 종료** 를 누릅니다. 시청 화면에 종료 안내가 뜨고 영상이 멈추며, 방송 목록에서 해당 라이브에 "종료" 배지가 붙고 시작·종료 시각이 보이는지 확인합니다. 종료된 라이브는 다시 방송 중이 되지 않습니다.
5. **연결 끊김 자동 종료**: 새 라이브를 시작하고 시청 화면을 연 뒤, 휴대폰의 송출 화면 탭을 닫거나 Wi-Fi를 끕니다. 시청 화면에 종료가 알려지고 목록에서 "종료"가 되는지 확인합니다.
6. **시청 정원 초과**: `CHZZK_VIEWER_CAPACITY=2 ./gradlew bootRun` 으로 정원을 2로 줄여 서버를 다시 띄웁니다. 새 라이브를 시작한 뒤 시청 화면을 3개 탭에서 엽니다. 3번째 탭에는 "시청자가 가득 찼습니다"가 보이고, 앞의 두 탭 중 하나를 닫은 뒤 새로고침하면 들어와 영상이 나오는지 확인합니다. 이때 방송은 영향받지 않습니다.
7. **재시작 후 상태**: 라이브를 시작하고 방송 중인 채로 서버를 `Ctrl+C` 로 끈 뒤 다시 띄웁니다. 목록에서 그 라이브가 "종료"로 보이고 제목·시작 시각이 남아 있는지 확인합니다(방송 중 상태로 돌아가지 않음).

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

### 4단계: 도네이션

화면의 "성공"이 아니라 **서버가 결제사에 승인을 확인했을 때만** 후원이 확정되는지 확인합니다. **아래 절차는 문서로만 정리한 것이고, 이 문서를 쓴 시점에 실제 토스 테스트 결제는 실행하지 않았습니다**(자동 테스트는 가짜 결제사로 같은 흐름을 검증합니다).

준비

1. 토스 테스트 키를 준비합니다([토스페이먼츠 테스트 키 준비](#토스페이먼츠-테스트-키-준비)).
2. 서버를 띄웁니다(깨끗한 상태는 먼저 `data/` 삭제): `TOSS_CLIENT_KEY=test_ck_<클라이언트키> TOSS_SECRET_KEY=test_sk_<시크릿키> ./gradlew bootRun`
3. 일반 창에서 방송 소유자(회원 A)로 가입해 녹화방송 하나를 등록하고, 시크릿 창에서 후원자(회원 B)로 가입합니다.

**브라우저로 확인**

1. **키 없음**: 키 없이 서버를 띄우면 방송 화면의 후원 영역에 "결제 키가 설정되지 않아 후원을 받을 수 없습니다."만 보이고 합계는 0원입니다. 다른 화면은 평소처럼 동작합니다.
2. **테스트 결제 성공**: B로 A의 방송 화면에서 금액(5,000원)과 메시지를 입력하고 **후원하기** 를 누릅니다. 토스 결제창에서 테스트 결제를 진행해 결제를 마칩니다(테스트 환경에서는 개발자센터 안내대로 테스트 카드 정보를 쓰며 실제 청구되지 않습니다). 결과 화면에 후원 확정이 보이고, 방송으로 돌아가면 합계 5,000원과 내역(B 닉네임·금액·메시지)이 보입니다.
3. **성공 페이지 새로고침**: 결과 화면(주소가 `/donations/success?...`)에서 새로고침을 여러 번 누릅니다. 같은 결과 화면이 나오고, 방송 화면의 합계는 5,000원 그대로이며 내역도 1건입니다(중복 적립 없음).
4. **결제 실패**: 후원하기 후 결제창에서 거절되는 테스트 결제(개발자센터의 테스트 안내 참고)를 진행합니다. "결제에 실패해 후원하지 않았습니다." 화면이 나오고 합계·내역은 그대로입니다.
5. **결제창 닫기(취소)**: 후원하기 후 결제창을 닫습니다(결제창의 취소). "결제를 취소해 후원하지 않았습니다." 화면과 다시 시도 링크가 나오고, 합계·내역은 그대로입니다. 다시 시도하면 새 후원 주문으로 결제할 수 있습니다.
6. **거절되는 후원**
   - 로그인하지 않은 창: 후원 폼 대신 "로그인 후 후원" 링크만 보입니다.
   - 소유자 A로 자기 방송: "이 방송에는 후원할 수 없습니다."만 보이고 폼이 없습니다.
   - 라이브 방송도 같습니다(2단계 절차로 라이브를 시작하고 시청 화면에서 확인).
7. **재시작**: 서버를 `Ctrl+C` 로 끄고 같은 `data/` 로 다시 띄웁니다. 확정된 후원과 합계가 그대로 남아 있습니다.

**curl로 확인: 화면 버튼이 아니라 서버가 거절·중복 방지를 한다**

토스 결제창 없이도 서버 응답을 볼 수 있는 항목입니다(깨끗한 `data/` 에서 시작해 녹화방송 번호가 1이라고 가정). [3단계 curl 예시](#3단계-인증인가와-댓글-1)의 도우미(`tok`, `code`, `refresh`)와 가입 절차를 먼저 실행해 `owner1.jar`(소유자), `other1.jar`(후원자), `anon.jar`(비로그인) 쿠키 파일과 소유자의 녹화방송(1번)이 있어야 합니다. 주석은 기대하는 결과입니다.

```bash
BASE=http://localhost:8080
J='Content-Type: application/json'
order() { curl -s -b "$1.jar" -H "$J" -H "X-XSRF-TOKEN: $(tok $1.jar)" \
          -d "{\"kind\":\"RECORDED\",\"broadcastId\":1,\"amount\":${2:-5000},\"message\":\"curl 후원\"}" $BASE/donations; }

# 비로그인 -> 401
curl -s -o /dev/null -w "%{http_code}\n" -b anon.jar -H "$J" -H "X-XSRF-TOKEN: $(tok anon.jar)" \
     -d '{"kind":"RECORDED","broadcastId":1,"amount":5000}' $BASE/donations
# CSRF 토큰 없이 -> 403
curl -s -o /dev/null -w "%{http_code}\n" -b other1.jar -H "$J" \
     -d '{"kind":"RECORDED","broadcastId":1,"amount":5000}' $BASE/donations
# 소유자가 자기 방송에 -> 400 {"message":"자기 방송에는 후원할 수 없습니다."}
order owner1
# 금액 규칙 위반(500원) -> 400 {"message":"후원 금액은 1,000원 단위로 ..."}
order other1 500
# 후원자 -> 201 {"orderId":"...","amount":5000,"orderName":"hello 후원","clientKey":"test_ck_...",...}
order other1
```

**성공 주소는 후원자 본인만**: 로그인하지 않고 열면 `302`(로그인 화면), 다른 회원(`owner1.jar`)으로 열면 `403` 이고 주문은 바뀌지 않습니다.

```bash
ORDER=<위 order other1 응답의 orderId>
curl -s -o /dev/null -w "%{http_code}\n" "$BASE/donations/success?paymentKey=fake&orderId=$ORDER&amount=5000"             # 302 -> /login
curl -s -o /dev/null -w "%{http_code}\n" -b owner1.jar "$BASE/donations/success?paymentKey=fake&orderId=$ORDER&amount=5000"   # 403
```

**금액을 바꾼 success 주소 거절**: 위 `order other1` 응답의 `orderId` 를 `ORDER` 에 넣고, 주문 금액(5,000원)과 다른 금액으로 성공 주소를 직접 엽니다. 서버는 토스에 승인 요청을 보내지 않고 주문을 `실패` 로 닫습니다.

```bash
ORDER=<orderId>
curl -s -o /dev/null -w "%{http_code}\n" -b other1.jar "$BASE/donations/success?paymentKey=fake&orderId=$ORDER&amount=1000"   # 400 (금액이 달라 후원을 확정하지 않았습니다)
curl -s $BASE/broadcasts/1 | grep -c "curl 후원"                                                                  # 0 (합계·내역에 없음)
```

**웹훅 재전송: 반복해도 합계가 늘지 않는다**: 웹훅은 토스가 이 서버를 호출하므로 공개 주소가 필요합니다. `cloudflared tunnel --url http://localhost:8080` 으로 얻은 주소를 `PUBLIC` 에 넣고, 개발자센터에 `$PUBLIC/donations/webhook` 을 등록해 둡니다(등록 없이 아래 curl만으로도 재전송을 흉내낼 수 있습니다). 브라우저에서 후원을 한 번 성공시킨 뒤 결과 화면 주소의 `paymentKey`·`orderId` 를 가져옵니다.

```bash
PUBLIC=https://<임의이름>.trycloudflare.com
PK=<결과 화면 주소의 paymentKey>; OID=<결과 화면 주소의 orderId>
for i in 1 2 3; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST $PUBLIC/donations/webhook -H 'Content-Type: application/json' \
       -d "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"$PK\",\"orderId\":\"$OID\"}}"   # 매번 200 (로그인·CSRF 토큰 불필요)
done
curl -s $BASE/broadcasts/1 | grep -o "후원 합계.*원"   # 합계가 웹훅 전과 같음(내역도 1건)
# 모르는 주문번호 -> 마찬가지로 200이고 아무것도 바뀌지 않음
curl -s -o /dev/null -w "%{http_code}\n" -X POST $PUBLIC/donations/webhook -H 'Content-Type: application/json' \
     -d '{"data":{"paymentKey":"x","orderId":"no-such-order"}}'
```

이미 확정된 주문의 웹훅·성공 주소 재호출은 토스를 다시 부르지 않고 같은 결과를 돌려줍니다. 결제를 마치지 않은 `대기` 주문에 웹훅이 오면 서버는 토스에 조회해 결제가 `DONE` 일 때만 확정하고, 그렇지 않으면 주문을 바꾸지 않습니다.

이 상태 코드와 결과는 자동 테스트(`DonationWebTest`, `DonationFailWebTest`, `DonationWebhookTest`, `DonationSafetyWebTest`, `DonationDisabledWebTest`, `DonationRestartPersistenceTest`)가 가짜 결제사로 같은 요청을 보내 검증합니다. 위의 토스 결제창·웹훅 등록·cloudflared 구간은 실제로 실행해 확인하지 않았으므로 직접 확인해 주세요.

### 5단계: 로컬 오토스케일

부하를 걸면 인스턴스 수가 늘고, 부하를 멈추면 다시 줄어드는지, 그동안 로그인 상태·방송 목록·댓글이 그대로인지 확인합니다. **아래 결과는 2026-11-16 이 저장소의 스크립트로 실제 kind 클러스터에서 실행한 출력입니다**(전체 기록: [docs/scale-check/](docs/scale-check/)). 실행 환경: macOS, Docker Desktop(CPU 8개, 메모리 4GB), kind 단일 노드.

준비

1. Docker를 켜고 kind·kubectl을 설치합니다(예: `brew install kind kubectl`).
2. `scripts/cluster-up.sh` 로 배포합니다. 끝나면 `kubectl --context kind-chzzk get pods` 에 `chzzk-…` 파드 2개, `postgres`, `redis` 가 `Running` 입니다.
3. 브라우저로 <http://localhost:8080> 에 접속해 가입·로그인하고 방송·댓글이 보이는지 봅니다. 응답 헤더 `X-Chzzk-Instance` 로 어느 인스턴스가 답했는지 볼 수 있습니다.

```bash
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -D - http://localhost:8080/broadcasts | grep -i x-chzzk-instance; done
# 두 파드 이름이 섞여 나오면 요청이 나뉘고 있는 것
```

스케일 아웃·인 확인

1. 터미널 A: `kubectl --context kind-chzzk get hpa,pods -w` (또는 15초마다 `kubectl --context kind-chzzk get hpa chzzk`)
2. 터미널 B: `scripts/check-state.sh` (확인용 회원을 만들어 로그인하고 방송·댓글을 하나 만든 뒤, 같은 세션으로 10초마다 확인)
3. 터미널 C: `scripts/load.sh` (k6 Job, 약 5분 30초)
4. 터미널 A에서 `REPLICAS` 가 2 → 3 → 5 로 늘었다가, 부하가 끝나고 약 1분 뒤부터 4 → 3 → 2 로 줄어드는지 봅니다.
5. 터미널 B의 모든 줄이 `로그인=유지 방송목록=유지 댓글=유지` 이고, `응답 인스턴스` 가 여러 파드로 바뀌는지 봅니다.
6. 끝나면 `scripts/cluster-down.sh` 로 정리합니다.

실제 결과: 인스턴스 수 변화 (15초 간격 기록 64줄 중 변화가 보이는 줄. `kubectl get hpa chzzk`, 열: 시각 · 이름 · 대상 · CPU 사용률/목표 · 최소 · 최대 · **현재 인스턴스 수** · 나이)

```
23:22:20  chzzk   Deployment/chzzk   cpu: 42%/50%   2     5     2     47m
23:22:36  chzzk   Deployment/chzzk   cpu: 58%/50%   2     5     2     47m
23:23:19  chzzk   Deployment/chzzk   cpu: 76%/50%   2     5     3     48m
23:26:11  chzzk   Deployment/chzzk   cpu: 362%/50%   2     5     3     51m
23:26:26  chzzk   Deployment/chzzk   cpu: 357%/50%   2     5     5     51m
23:26:58  chzzk   Deployment/chzzk   cpu: 432%/50%   2     5     5     52m
23:29:37  chzzk   Deployment/chzzk   cpu: 30%/50%   2     5     5     54m
23:31:56  chzzk   Deployment/chzzk   cpu: 22%/50%   2     5     5     57m
23:32:11  chzzk   Deployment/chzzk   cpu: 17%/50%   2     5     4     57m
23:32:42  chzzk   Deployment/chzzk   cpu: 14%/50%   2     5     3     57m
23:33:13  chzzk   Deployment/chzzk   cpu: 6%/50%   2     5     2     58m
23:39:36  chzzk   Deployment/chzzk   cpu: 7%/50%   2     5     2     64m
```

- 부하 시작 직후 CPU가 목표 50%를 넘어 **2 → 3**, 부하 유지 중 **3 → 5**(최대)로 늘었습니다.
- 부하가 끝난 뒤(23:28 무렵) CPU가 내려가고, 축소 안정화 60초가 지난 뒤 **5 → 4 → 3 → 2**(최소)로 줄었습니다. 그 뒤로는 2개를 유지했습니다.
- 중간의 `<unknown>` 은 새 파드가 막 떠서 metrics-server가 아직 지표를 모으지 못한 구간입니다.

실제 결과: 스케일 중 상태 유지 (`scripts/check-state.sh`, 10초 간격 91번 중 일부)

```
확인용 회원 check1794838940, 방송 /broadcasts/2, 댓글 1개를 만들었습니다. 10초마다 확인합니다.
23:22:22  응답 인스턴스=chzzk-58c7bf9969-x6pbp     로그인=유지 방송목록=유지 댓글=유지
23:26:05  응답 인스턴스=chzzk-58c7bf9969-x6pbp     로그인=유지 방송목록=유지 댓글=유지
23:26:15  응답 인스턴스=chzzk-58c7bf9969-ql52s     로그인=유지 방송목록=유지 댓글=유지
23:26:30  응답 인스턴스=chzzk-58c7bf9969-sj7n7     로그인=유지 방송목록=유지 댓글=유지
23:26:41  응답 인스턴스=chzzk-58c7bf9969-ql52s     로그인=유지 방송목록=유지 댓글=유지
23:34:41  응답 인스턴스=chzzk-58c7bf9969-ql52s     로그인=유지 방송목록=유지 댓글=유지
```

- 91번 모두 `로그인=유지 방송목록=유지 댓글=유지` 였고, 응답한 인스턴스는 5개 파드에 걸쳐 바뀌었습니다(전체: [docs/scale-check/2026-11-16-state.log](docs/scale-check/2026-11-16-state.log)).
- k6 결과: 요청 597,305건, 실패 0%, 체크 100% 통과, p95 응답 106ms ([docs/scale-check/2026-11-16-k6-summary.txt](docs/scale-check/2026-11-16-k6-summary.txt)).

라이브 확인 (클러스터 모드)

1. 브라우저에서 라이브를 시작만 하고 방송자 화면을 열지 않으면, 접속 유예 30초 뒤 방송이 종료되고 열려 있던 시청 화면에 새로고침 없이 "방송이 종료되었습니다." 가 뜹니다(종료 판정과 종료 알림이 인스턴스 사이에 전달됨. 위 날짜에 브라우저로 확인).
2. 휴대폰으로 송출하려면 [휴대폰 연결 방법](#휴대폰-연결-방법)처럼 `cloudflared tunnel --url http://localhost:8080` 으로 HTTPS 주소를 만들어 송출 화면을 엽니다. 시청 화면을 다른 기기에서 열면 방송자와 시청자가 서로 다른 인스턴스에 붙어도 영상이 보입니다.
3. 방송 중에 `kubectl --context kind-chzzk delete pod <앱 파드 하나>` 로 인스턴스를 하나 없애도 방송은 종료되지 않고, 화면은 다시 접속합니다(방송자 연결이 끊긴 채 30초가 지나면 종료). 실제 휴대폰 송출은 이 문서를 쓰며 실행하지 않았고, 같은 동작을 `ClusterLiveTest` 가 확인합니다.
