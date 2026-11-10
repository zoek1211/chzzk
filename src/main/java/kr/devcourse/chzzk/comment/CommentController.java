package kr.devcourse.chzzk.comment;

import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 댓글 작성. 로그인 여부는 보안 설정이 막고, 작성자는 세션의 회원이다. */
@Controller
class CommentController {

    private final CommentService comments;

    CommentController(CommentService comments) {
        this.comments = comments;
    }

    @PostMapping("/broadcasts/{id}/comments")
    String writeOnRecorded(@PathVariable Long id, @RequestParam(defaultValue = "") String body,
                           @AuthenticationPrincipal MemberPrincipal me) {
        comments.add(BroadcastKind.RECORDED, id, me.memberId(), body);
        return "redirect:/broadcasts/" + id + "#comments";
    }

    @PostMapping("/live/{id}/comments")
    String writeOnLive(@PathVariable Long id, @RequestParam(defaultValue = "") String body,
                       @AuthenticationPrincipal MemberPrincipal me) {
        comments.add(BroadcastKind.LIVE, id, me.memberId(), body);
        return "redirect:/live/" + id + "#comments";
    }

    @ExceptionHandler(InvalidCommentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String rejectComment(InvalidCommentException e, Model model) {
        model.addAttribute("error", e.getMessage());
        return "comment/rejected";
    }
}
