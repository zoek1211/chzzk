# 구현 에이전트 공통 지침

티켓 하나를 맡은 구현 에이전트가 따르는 규칙. 오케스트레이터는 프롬프트에 **티켓 번호, 통합 브랜치(`wip/step-N`)와 기준 커밋, 앞선 티켓의 커밋·공개 인터페이스, 동시에 작업 중인 티켓**만 적고 나머지는 이 문서를 가리킨다.

## 시작

1. `gh issue view <스펙 번호>`와 `gh issue view <티켓 번호>`로 스펙과 수용 기준을 읽는다. `GLOSSARY.md`의 용어로 이름과 테스트를 짓고, `docs/adr/`에서 관련 결정을 확인한다.
2. 워크트리가 통합 브랜치의 기준 커밋 이후인지 확인한다. 아니면 `git fetch origin && git reset --hard origin/wip/step-N`. 작업 브랜치는 `ticket-<번호>`.
3. Skill 도구로 `mattpocock-skills:tdd`를 불러, 스펙이 정한 경계에서 실패하는 테스트부터 한 조각씩 쌓는다.

## 테스트

- 경계: HTTP는 `TestWeb.mvc(context)`(실제 보안 필터 + CSRF), 로그인은 `MemberSessions`/`TestLogin` 도우미, WebSocket은 `RANDOM_PORT` + 실제 클라이언트. 저장소(Repository)를 직접 조회하지 않고 공개 경계의 응답으로 확인한다.
- 데이터는 `@TempDir` 임시 디렉터리에 둔다. 앱을 두 번 띄우는 재시작 테스트는 `--chzzk.data-dir=...` 커맨드라인 인자로 넘긴다. 테스트가 `./data`를 만들면 `./gradlew test`가 실패한다.
- 동시에 작업 중인 티켓이 있으면 공유 파일(`SecurityConfig`, 공용 템플릿)은 자기 경로·조각만 고친다.

## 마무리

- 커밋 메시지 끝: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- 보고 전에 통합 브랜치 최신 커밋을 자기 브랜치에 합치고 `./gradlew test` 전체가 통과하는지 확인한다.
- push, 태그, 이슈 코멘트·닫기는 오케스트레이터가 한다. 구현 에이전트는 자기 브랜치에 커밋까지만 한다.
- 보고(200단어 이내): 브랜치, 워크트리 경로, head 커밋, 테스트 수, 추가·변경한 엔드포인트와 다음 티켓이 쓸 공개 인터페이스, 스펙과 다르게 한 점.

## 환경

- Bash에서 Gradle이 훅에 막히면 context-mode의 `ctx_execute`(shell, cwd = 워크트리)로 돌리고 실패·요약만 출력한다.
