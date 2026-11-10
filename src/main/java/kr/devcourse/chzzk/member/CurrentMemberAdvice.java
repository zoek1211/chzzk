package kr.devcourse.chzzk.member;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면의 헤더가 로그인 상태를 그릴 수 있게 현재 회원을 모델에 싣는다(비로그인이면 null). */
@ControllerAdvice
class CurrentMemberAdvice {

    @ModelAttribute("currentMember")
    MemberPrincipal currentMember(@AuthenticationPrincipal MemberPrincipal principal) {
        return principal;
    }
}
