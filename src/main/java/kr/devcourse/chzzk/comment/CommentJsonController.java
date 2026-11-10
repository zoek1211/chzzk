package kr.devcourse.chzzk.comment;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 댓글 목록 JSON(공개). 라이브 시청 화면이 주기적으로 읽는다. 없는 방송은 404. */
@RestController
class CommentJsonController {

    /** JSON 한 건. {@code edited}는 작성 뒤 수정된 적이 있는지. */
    record CommentJson(Long id, Long authorId, String authorNickname, String body, Instant createdAt,
                       boolean edited) {
    }

    private final CommentService comments;

    CommentJsonController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/broadcasts/{id}/comments")
    List<CommentJson> recorded(@PathVariable Long id) {
        return json(BroadcastKind.RECORDED, id);
    }

    @GetMapping("/live/{id}/comments")
    List<CommentJson> live(@PathVariable Long id) {
        return json(BroadcastKind.LIVE, id);
    }

    private List<CommentJson> json(BroadcastKind kind, Long id) {
        return comments.listExisting(kind, id).stream()
                .map(c -> new CommentJson(c.id(), c.authorId(), c.authorNickname(), c.body(), c.createdAt(), c.edited()))
                .toList();
    }
}
