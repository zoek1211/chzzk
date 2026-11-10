package kr.devcourse.chzzk.member;

import java.util.List;

/** 가입 규칙 위반 또는 아이디 중복. 사용자에게 보여 줄 이유 목록을 담는다. */
public class InvalidSignUpException extends RuntimeException {

    private final List<String> reasons;

    InvalidSignUpException(List<String> reasons) {
        super(String.join(" ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> getReasons() {
        return reasons;
    }
}
