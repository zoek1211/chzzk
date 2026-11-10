package kr.devcourse.chzzk.comment;

import java.util.List;
import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 댓글 목록 JSON(공개). 라이브 시청 화면이 주기적으로 읽는다. 없는 방송은 404.
 * 각 건의 {@code mine}은 요청한 회원이 쓴 댓글인지(비로그인이면 false)다.
 */
@RestController
class CommentJsonController {

    private final CommentService comments;

    CommentJsonController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/broadcasts/{id}/comments")
    List<CommentService.CommentView> recorded(@PathVariable Long id, @AuthenticationPrincipal MemberPrincipal me) {
        return comments.listExisting(BroadcastKind.RECORDED, id, memberId(me));
    }

    @GetMapping("/live/{id}/comments")
    List<CommentService.CommentView> live(@PathVariable Long id, @AuthenticationPrincipal MemberPrincipal me) {
        return comments.listExisting(BroadcastKind.LIVE, id, memberId(me));
    }

    private static Long memberId(MemberPrincipal me) {
        return me == null ? null : me.memberId();
    }
}
