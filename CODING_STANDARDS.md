# 코딩 표준

리뷰 에이전트가 diff를 볼 때 적용하는 판단 규칙. 기계적으로 검사되는 규칙(테스트의 `./data` 사용, README 누적)은 CI가 맡으므로 여기 두지 않는다. 용어는 `GLOSSARY.md`, 되돌리기 어려운 결정은 `docs/adr/`를 따른다.

## 모듈 경계

- 패키지는 도메인 단위다: `member`, `broadcast`(녹화방송), `live`(라이브 방송), `comment`, `video`, `list`. `live`는 `broadcast`를 import하지 않는다.
- 방송 모듈 → 댓글 모듈 방향의 의존은 이벤트로만 흐른다(`RecordedBroadcastDeleted`, `LiveDeleted`). 방송 모듈은 `comment`를 모른다.
- 소유자 판단은 방송 모듈이 답한다("이 회원이 소유자인가"). 컨트롤러는 그 결과를 응답 코드로 바꾸기만 한다.
- 트랜잭션·이벤트 발행·파일 정리는 서비스가 맡는다. 컨트롤러는 `EntityManager`를 쓰지 않는다.

## 권한

- 모든 관리·송출 제어·작성자 전용 요청은 **서버**가 로그인과 소유자/작성자를 검사한다. 템플릿의 버튼 숨김은 편의일 뿐 검사로 치지 않는다.
- 소유자 없는 방송(회원 기능 이전 데이터)은 누구도 관리하지 못한다.
- 상태를 바꾸는 요청은 CSRF 토큰을 요구한다. 예외는 `/ws/**`이며 그 경로는 핸드셰이크에서 세션의 소유자를 검사한다.
- 비로그인 응답: HTML 폼 경로는 로그인 화면으로 302, 라이브·fetch(JSON) 경로는 401. 새 경로는 이 구분을 따른다.

## 오류 처리

- 서비스는 작은 도메인 예외를 던지고, `@ExceptionHandler`/`@ResponseStatus`가 응답 코드로 바꾼다. 서비스에서 `ResponseStatusException`을 던지지 않는다.
- 사용자에게 보이는 오류 문구는 한국어 한 문장으로, 무엇을 고치면 되는지 적는다.

## 화면

- 사용자가 입력한 글은 `th:text` 또는 `textContent`로만 넣는다. `th:utext`, 사용자 글을 담은 `innerHTML`은 쓰지 않는다.
- 서버 렌더링과 자바스크립트 렌더링은 같은 형식을 낸다(예: 시각 `yyyy-MM-dd HH:mm`, Asia/Seoul).
- 회원의 DB id를 화면이나 JSON에 노출하지 않는다. 버튼 표시는 서버가 계산한 `mine` 같은 불리언으로 정한다.

## README

- 단계별 누적 규칙은 `docs/agents/assignment-workflow.md`의 "README 규칙"을 따르고, CI(`scripts/check_readme_stages.py`)가 검사한다.
- README의 경로·응답 코드·환경변수 설명은 코드와 일치해야 한다. 리뷰어는 이번 diff가 바꾼 경로를 README와 대조한다.
