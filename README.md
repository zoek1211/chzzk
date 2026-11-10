# 치지직 만들기 ① 인증·인가 없는 녹화방송

로그인 없이 녹화된 방송(다시보기)을 등록하고, 목록에서 골라 상세 화면에서 재생하는 서비스입니다.

- Java 25 이상, Spring Boot 4.1, Spring MVC + Thymeleaf, Spring Data JPA
- 저장소: 파일 기반 H2 DB(녹화방송 정보) + 로컬 디스크(업로드 영상)

용어는 [GLOSSARY.md](GLOSSARY.md)에 정리했습니다.

## 실행 방법

필요한 것: JDK 25 이상 (Gradle은 래퍼가 내려받습니다)

```bash
./gradlew bootRun
```

브라우저에서 <http://localhost:8080> 을 엽니다. `/broadcasts` 목록 화면으로 이동합니다.

jar로 실행하려면:

```bash
./gradlew bootJar
java -jar build/libs/chzzk-0.0.1-SNAPSHOT.jar
```

테스트:

```bash
./gradlew test
```

## 환경변수

모두 선택 사항입니다. 예시는 [.env.example](.env.example)에 있습니다. 이 단계에는 비밀번호·토큰 같은 비밀 값이 필요 없습니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `PORT` | `8080` | 서버 포트 |
| `CHZZK_DATA_DIR` | `./data` | DB 파일(`chzzk.mv.db`)과 업로드 영상(`videos/`)이 저장되는 디렉터리 |
| `CHZZK_MAX_UPLOAD_SIZE` | `2GB` | 업로드 영상 최대 크기 |

예시:

```bash
PORT=9090 CHZZK_DATA_DIR=/var/lib/chzzk ./gradlew bootRun
```

## 이번 단계 구현 범위

| 기능 | 위치 |
| --- | --- |
| 녹화방송 등록: 제목 + **영상 파일 업로드** 또는 **이미 저장된 영상 주소(http/https)** 중 하나 | `GET /broadcasts/new` 폼, `POST /broadcasts` |
| 등록 검증: 제목 필수(200자 이하), 영상 출처는 정확히 하나, 영상 주소는 http/https만. 위반 시 400과 함께 폼에 오류 표시 | `RecordedBroadcast`, `RecordedBroadcastController` |
| 녹화방송 목록 (최신 등록순) | `GET /broadcasts` (`/` 는 여기로 이동) |
| 상세 화면 + HTML5 영상 플레이어 | `GET /broadcasts/{id}` |
| 업로드 영상 전송 (Range 요청 지원, 탐색 가능) | `GET /videos/{파일명}` |
| 영속 저장: 녹화방송 정보(제목, 영상 출처, 영상 위치, 등록 시각)는 H2 파일 DB에, 업로드 영상은 디스크에 저장 → 재시작 후에도 유지 | `RecordedBroadcastRepository`, `VideoStorage` |
| 로그인 없음: 모든 화면과 기능을 누구나 사용 | Spring Security 미사용 |

이번 단계에서 하지 않는 것: 인증·인가, 라이브 송출, 댓글, 도네이션, 녹화방송 수정·삭제.

### 코드 구조

```
src/main/java/kr/devcourse/chzzk/
├── broadcast/
│   ├── RecordedBroadcast.java            녹화방송 엔티티 + 등록 규칙
│   ├── VideoSource.java                  영상 출처 (UPLOADED / EXTERNAL)
│   ├── RecordedBroadcastRepository.java  JPA 저장소
│   ├── RecordedBroadcastController.java  등록·목록·상세 화면
│   └── InvalidRegistrationException.java
└── video/
    ├── VideoStorage.java                 업로드 영상 디스크 보관
    └── VideoController.java              업로드 영상 전송
src/main/resources/templates/broadcasts/  list.html, new.html, detail.html
```

### 자동 테스트

- `RecordedBroadcastWebTest`: HTTP 요청으로 등록(업로드/외부 주소) → 목록 → 상세 플레이어 → 영상 전송, 잘못된 등록 거부, 없는 방송 404
- `RestartPersistenceTest`: 같은 데이터 디렉터리로 애플리케이션을 두 번 띄워, 재시작 후에도 녹화방송 정보와 업로드 영상이 남는지 확인

## 직접 확인 절차

1. `./gradlew bootRun` 으로 서버를 띄우고 <http://localhost:8080> 을 엽니다. 처음에는 "아직 등록된 녹화방송이 없습니다"가 보입니다.
2. 오른쪽 위 **녹화방송 등록**을 누릅니다.
3. 제목을 입력하고 **영상 파일 업로드**에서 mp4 파일을 고른 뒤 **등록**을 누릅니다.
   - 상세 화면으로 이동하고, 플레이어에서 영상이 재생되는지 확인합니다. 재생 바로 원하는 위치로 이동해 봅니다.
4. 다시 **녹화방송 등록**에서 제목과 **이미 저장된 영상 주소**(예: 공개된 mp4 주소)를 입력해 등록합니다.
   - 상세 화면에서 그 주소의 영상이 재생되는지 확인합니다.
5. 잘못된 입력을 확인합니다.
   - 영상 없이 제목만 등록 → "영상 파일을 올리거나 영상 주소를 입력해 주세요." 오류가 보이고 입력한 제목은 유지됩니다.
   - 파일과 주소를 둘 다 넣어 등록 → "하나만 입력해 주세요." 오류
   - 주소에 `ftp://...` 입력 → http/https 오류
6. 상단 **치지직 다시보기** 로고를 눌러 목록으로 가서, 등록한 두 방송이 최신순으로 보이는지 확인하고 각각 눌러 상세로 이동합니다.
7. 서버를 `Ctrl+C`로 끄고 다시 `./gradlew bootRun` 으로 띄웁니다.
   - 목록에 두 방송이 그대로 있고, 업로드한 영상이 여전히 재생되는지 확인합니다.
8. 처음 상태로 되돌리려면 서버를 끈 뒤 `data/` 디렉터리를 지웁니다.

curl로 확인할 수도 있습니다:

```bash
curl -i -F title='테스트 다시보기' -F videoFile=@sample.mp4 http://localhost:8080/broadcasts
curl -i -F title='외부 영상' -F videoUrl=https://example.com/video.mp4 http://localhost:8080/broadcasts
```

응답의 `Location` 헤더가 새 녹화방송의 상세 화면 주소입니다.
