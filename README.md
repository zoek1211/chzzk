# 치지직 만들기 ② 휴대폰 라이브 방송

로그인 없이 **녹화방송(다시보기)** 을 등록·시청하고(1단계), 휴대폰 카메라·마이크로 **라이브 방송** 을 송출해 다른 화면에서 실시간으로 시청하는(2단계) 서비스입니다.

- Java 25 이상, Spring Boot 4.1, Spring MVC + Thymeleaf, Spring Data JPA, Spring WebSocket
- 저장소: 파일 기반 H2 DB(녹화방송·라이브 방송 정보) + 로컬 디스크(업로드 영상)
- 라이브 영상 전달: WebRTC P2P (서버는 WebSocket 시그널링만 중계) — 선택 이유는 [ADR 0001](docs/adr/0001-webrtc-p2p-live.md)

용어는 [GLOSSARY.md](GLOSSARY.md)에 정리했습니다.

## 실행 방법

필요한 것: JDK 25 이상 (Gradle은 래퍼가 내려받습니다)

```bash
./gradlew bootRun
```

브라우저에서 <http://localhost:8080> 을 엽니다. `/broadcasts` 방송 목록 화면으로 이동합니다.

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

2단계(휴대폰 라이브 방송)를 추가했고, **1단계 녹화방송 기능은 그대로 유지**됩니다(등록·목록·상세 재생·재시작 후 유지).

### 2단계: 라이브 방송

| 기능 | 위치 |
| --- | --- |
| 방송 시작: 제목 입력 + 카메라·마이크 미리보기 후 시작. 방송자에게만 송출 키를 줌 | `GET /live/new`, `POST /live` |
| WebRTC 시그널링 (방송자는 송출 키 필요, 시청자는 누구나) | `/ws/live/{id}/broadcaster?key=…`, `/ws/live/{id}/viewer` |
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

로그인이 없으므로 "방송을 시작한 바로 그 화면"이 방송자입니다.

- 방송 시작 시 서버가 **송출 키**(비밀 값)를 만들어 그 화면에만 알려 줍니다. 이 키로만 그 방송을 송출·종료할 수 있고, 목록·시청 화면에는 나타나지 않습니다. 서버에는 원문이 저장되지 않습니다.
- **시청자는 누구나** 로그인 없이 방송 목록에서 라이브를 골라 봅니다.
- 송출 화면을 닫거나 새로고침해 연결이 끊기면 그 방송은 종료되고 다시 시작할 수 없습니다.

### 코드 구조

```
src/main/java/kr/devcourse/chzzk/
├── broadcast/                            1단계 녹화방송
│   ├── RecordedBroadcast.java            녹화방송 엔티티 + 등록 규칙
│   ├── VideoSource.java                  영상 출처 (UPLOADED / EXTERNAL)
│   ├── RecordedBroadcastRepository.java  JPA 저장소
│   ├── RecordedBroadcastController.java  등록·목록·상세 화면
│   └── InvalidRegistrationException.java
├── video/
│   ├── VideoStorage.java                 업로드 영상 디스크 보관
│   └── VideoController.java              업로드 영상 전송
└── live/                                 2단계 라이브 방송
    ├── LiveBroadcast.java                라이브 엔티티 (제목, 상태, 시작·종료 시각, 송출 키 해시)
    ├── LiveStatus.java                   방송 중 / 종료
    ├── LiveBroadcastRepository.java      JPA 저장소
    ├── LiveBroadcastService.java         시작·종료·송출 키 검증
    ├── LiveBroadcastController.java      송출·시청 화면, 시작·종료 API
    ├── SignalingConfig.java              WebSocket 경로 등록, 접속 허용 검사
    ├── SignalingHandler.java             WebRTC 시그널링 중계, 정원, 끊김 시 종료
    ├── LiveEnded.java                    종료 이벤트
    ├── LiveStartupCleanup.java           재시작 시 방송 중 → 종료
    ├── IceServers.java                   STUN/TURN 설정 제공
    └── (예외) InvalidBroadcastKeyException, LiveNotFoundException
src/main/resources/templates/             broadcasts/*, live/new.html(송출), live/watch.html(시청)
docs/adr/0001-webrtc-p2p-live.md          WebRTC P2P 선택 기록
```

### 자동 테스트

`./gradlew test` 로 전체를 실행합니다.

- 1단계
  - `RecordedBroadcastWebTest`: 등록(업로드/외부 주소) → 목록 → 상세 플레이어 → 영상 전송, 잘못된 등록 거부, 없는 방송 404
  - `RestartPersistenceTest`: 재시작 후에도 녹화방송 정보와 업로드 영상이 남는지 확인
- 2단계
  - `LiveBroadcastWebTest`: 라이브 시작(송출 키 발급), 제목 검증, 방송 목록·시청 화면·송출 화면 구성, 송출 키 비노출
  - `LiveEndWebTest`: 송출 키로 종료, 틀린 키 403, 없는 방송 404, 중복 종료
  - `SignalingTest`: 송출 키 검사, 방송자·시청자 입장/퇴장 알림, offer·answer·candidate 전달 범위
  - `LiveEndSignalingTest`: 방송자 연결 끊김·종료 요청 시 시청자에게 종료 알림과 연결 종료
  - `ViewerCapacityTest`: 정원 초과 거부, 자리 비면 입장, 방송별 정원
  - `IceServersTest`: 공개 STUN 기본, TURN 설정 시 화면에 전달
  - `LiveRestartPersistenceTest`: 재시작 후 라이브 정보 유지, 방송 중 → 종료, 송출 키 원문 미저장
  - `HttpsConfigTest`: 환경변수가 없으면 HTTP가 기본

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

4. 출력에 나오는 `https://<임의이름>.trycloudflare.com` 주소를 휴대폰 브라우저에서 열고 **송출 화면**(`/live/new`)으로 갑니다. 같은 주소의 `/live/{id}` 를 다른 화면에서 열어 시청합니다.

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

### 2단계: 라이브 방송

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
