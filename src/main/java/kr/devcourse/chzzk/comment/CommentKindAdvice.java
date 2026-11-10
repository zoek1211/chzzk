package kr.devcourse.chzzk.comment;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 화면 템플릿이 댓글 조각에 방송 종류를 문자열 없이 넘길 수 있게 두 종류를 모델에 싣는다. */
@ControllerAdvice
class CommentKindAdvice {

    @ModelAttribute("recordedKind")
    BroadcastKind recordedKind() {
        return BroadcastKind.RECORDED;
    }

    @ModelAttribute("liveKind")
    BroadcastKind liveKind() {
        return BroadcastKind.LIVE;
    }
}
