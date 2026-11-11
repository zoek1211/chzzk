#!/usr/bin/env python3
"""README 누적 규칙 검사.

각 `step-*` 태그 시점 README의 `### N단계…` 소단원이 지금 README에도 원문 그대로 남아 있는지 확인한다.
허용되는 차이는 뒤 단계가 붙인 안내 줄(`> N단계에서 바뀐 점: …`)뿐이다.
규칙: docs/agents/assignment-workflow.md 의 "README 규칙".

사용: python3 scripts/check_readme_stages.py [README 경로]   (저장소 루트에서, 태그가 fetch된 상태)
"""
import re
import subprocess
import sys

STAGE_HEADING = re.compile(r"^### \d+단계")
SECTION_END = re.compile(r"^#{1,3} ")
NOTE_LINE = re.compile(r"^> \d+단계에서 바뀐 점")


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def stage_sections(text):
    """(상위 ## 제목, ### 제목) → 본문 줄 목록. 코드 블록 안의 '# ' 줄은 제목으로 보지 않는다."""
    sections, parent, current, in_code = {}, None, None, False
    for line in text.splitlines():
        if line.startswith("```"):
            in_code = not in_code
        if not in_code and SECTION_END.match(line):
            current = None
            if line.startswith("## "):
                parent = line
            if STAGE_HEADING.match(line):
                current = (parent, line)
                sections[current] = []
            continue
        if current is not None and not NOTE_LINE.match(line):
            sections[current].append(line)
    return {key: "\n".join(body).strip() for key, body in sections.items()}


def main():
    readme = sys.argv[1] if len(sys.argv) > 1 else "README.md"
    current = stage_sections(open(readme, encoding="utf-8").read())
    tags = sorted(t for t in git("tag", "--list", "step-*").split() if t)
    if not tags:
        print("step-* 태그가 없습니다. `git fetch --tags` 후 다시 실행하세요.")
        return 1
    problems = []
    for tag in tags:
        old = stage_sections(git("show", f"refs/tags/{tag}:README.md"))
        for key, body in old.items():
            if key not in current:
                problems.append(f"[{tag}] 소단원이 사라졌습니다: {key[0]} > {key[1]}")
            elif current[key] != body:
                problems.append(f"[{tag}] 소단원 원문이 바뀌었습니다: {key[0]} > {key[1]}")
    if problems:
        print("README 누적 규칙 위반 (docs/agents/assignment-workflow.md 'README 규칙' 참고):")
        print("\n".join("  - " + p for p in problems))
        print("앞 단계 소단원은 원문 그대로 두고, 바뀐 점은 소단원 맨 위 '> N단계에서 바뀐 점: …' 한 줄로만 적습니다.")
        return 1
    print(f"README 누적 규칙 통과 ({', '.join(tags)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
