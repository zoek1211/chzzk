package kr.devcourse.chzzk.comment;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastRepository;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastDeleted;
import kr.devcourse.chzzk.live.LiveBroadcastRepository;
import kr.devcourse.chzzk.live.LiveDeleted;
import kr.devcourse.chzzk.member.Member;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 댓글 모듈의 공개 인터페이스. 수정·삭제는 같은 모듈에 이어서 붙인다(작성자 판단은 작성자 id로).
 * 화면은 Thymeleaf에서 {@code @commentService.list(...)}로 목록을 읽는다.
 */
@Service
public class CommentService {

    /** 화면에 보이는 댓글 한 건. */
    public record CommentView(Long id, Long authorId, String authorNickname, String body, Instant createdAt,
                              boolean edited) {
    }

    private final CommentRepository comments;
    private final RecordedBroadcastRepository recorded;
    private final LiveBroadcastRepository live;
    private final EntityManager entityManager;

    CommentService(CommentRepository comments, RecordedBroadcastRepository recorded,
                   LiveBroadcastRepository live, EntityManager entityManager) {
        this.comments = comments;
        this.recorded = recorded;
        this.live = live;
        this.entityManager = entityManager;
    }

    /** 방송의 댓글을 오래된 순으로 돌려준다. 누구나 읽을 수 있다. */
    @Transactional(readOnly = true)
    public List<CommentView> list(BroadcastKind kind, Long broadcastId) {
        return comments.findOldestFirst(kind, broadcastId).stream()
                .map(c -> new CommentView(c.getId(), c.getAuthor().getId(), c.getAuthor().getNickname(),
                        c.getBody(), c.getCreatedAt(), c.getUpdatedAt() != null))
                .toList();
    }

    /** {@link #list(BroadcastKind, Long)}와 같지만 없는 방송이면 404. */
    @Transactional(readOnly = true)
    public List<CommentView> listExisting(BroadcastKind kind, Long broadcastId) {
        if (!broadcastExists(kind, broadcastId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return list(kind, broadcastId);
    }

    /** 템플릿에서 문자열 종류로 부르기 위한 편의 메서드. */
    @Transactional(readOnly = true)
    public List<CommentView> list(String kind, Long broadcastId) {
        return list(BroadcastKind.valueOf(kind), broadcastId);
    }

    /** 회원이 방송에 댓글을 단다. 없는 방송이면 404, 본문 규칙 위반이면 {@link InvalidCommentException}. */
    @Transactional
    public CommentView add(BroadcastKind kind, Long broadcastId, Long memberId, String body) {
        if (!broadcastExists(kind, broadcastId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String validBody = validBody(body);
        Member author = entityManager.getReference(Member.class, memberId);
        Comment saved = comments.save(new Comment(kind, broadcastId, author, validBody));
        return new CommentView(saved.getId(), memberId, author.getNickname(), saved.getBody(), saved.getCreatedAt(), false);
    }

    /** 수정·삭제가 끝난 댓글이 달린 방송(화면으로 돌아가는 데 쓴다). */
    public record Target(BroadcastKind kind, Long broadcastId) {
    }

    /** 작성자만 댓글을 고칠 수 있다. 없으면 404, 작성자가 아니면 403, 본문 규칙 위반이면 {@link InvalidCommentException}. */
    @Transactional
    public Target edit(Long commentId, Long memberId, String body) {
        Comment comment = authoredComment(commentId, memberId);
        comment.edit(validBody(body));
        return new Target(comment.getKind(), comment.getBroadcastId());
    }

    /** 작성자만 댓글을 지울 수 있다. 없으면 404, 작성자가 아니면 403. */
    @Transactional
    public Target delete(Long commentId, Long memberId) {
        Comment comment = authoredComment(commentId, memberId);
        comments.delete(comment);
        return new Target(comment.getKind(), comment.getBroadcastId());
    }

    private Comment authoredComment(Long commentId, Long memberId) {
        Comment comment = comments.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!comment.getAuthor().getId().equals(memberId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return comment;
    }

    /** 방송이 삭제되면 그 방송의 댓글도 함께 지운다(방송 삭제와 같은 트랜잭션). */
    @EventListener
    void onRecordedDeleted(RecordedBroadcastDeleted event) {
        comments.deleteByBroadcast(BroadcastKind.RECORDED, event.broadcastId());
    }

    @EventListener
    void onLiveDeleted(LiveDeleted event) {
        comments.deleteByBroadcast(BroadcastKind.LIVE, event.liveId());
    }

    private boolean broadcastExists(BroadcastKind kind, Long id) {
        return switch (kind) {
            case RECORDED -> recorded.existsById(id);
            case LIVE -> live.existsById(id);
        };
    }

    private static String validBody(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidCommentException("댓글 내용을 입력해 주세요.");
        }
        if (body.length() > Comment.MAX_BODY_LENGTH) {
            throw new InvalidCommentException("댓글은 " + Comment.MAX_BODY_LENGTH + "자 이하로 입력해 주세요.");
        }
        return body;
    }
}
