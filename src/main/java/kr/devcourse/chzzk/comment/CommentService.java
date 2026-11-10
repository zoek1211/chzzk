package kr.devcourse.chzzk.comment;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastDeleted;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastRepository;
import kr.devcourse.chzzk.live.LiveBroadcastRepository;
import kr.devcourse.chzzk.live.LiveDeleted;
import kr.devcourse.chzzk.member.Member;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 모듈의 공개 인터페이스: 목록·작성·수정·삭제(수정·삭제는 작성자만).
 * 화면은 Thymeleaf에서 {@code @commentService.list(...)}로 목록을 읽는다.
 */
@Service
public class CommentService {

    /**
     * 화면과 JSON이 함께 쓰는 댓글 한 건. {@code updatedAt}은 수정된 적이 없으면 null,
     * {@code mine}은 목록을 읽는 회원이 작성자인지(회원 id는 밖으로 내지 않는다).
     */
    public record CommentView(Long id, String authorNickname, String body, Instant createdAt, Instant updatedAt,
                              boolean mine) {
    }

    /** 수정·삭제가 끝난 댓글이 달린 방송(화면으로 돌아가는 데 쓴다). */
    public record Target(BroadcastKind kind, Long broadcastId) {
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

    /** 방송의 댓글을 오래된 순으로 돌려준다. 누구나 읽을 수 있고, {@code viewerId}(비로그인이면 null)의 댓글은 mine이 된다. */
    @Transactional(readOnly = true)
    public List<CommentView> list(BroadcastKind kind, Long broadcastId, Long viewerId) {
        return comments.findOldestFirst(kind, broadcastId).stream()
                .map(c -> view(c, viewerId))
                .toList();
    }

    /** {@link #list}와 같지만 없는 방송이면 {@link BroadcastNotFoundException}. */
    @Transactional(readOnly = true)
    public List<CommentView> listExisting(BroadcastKind kind, Long broadcastId, Long viewerId) {
        requireBroadcast(kind, broadcastId);
        return list(kind, broadcastId, viewerId);
    }

    /** 회원이 방송에 댓글을 단다. 없는 방송이면 {@link BroadcastNotFoundException}, 본문 규칙 위반이면 {@link InvalidCommentException}. */
    @Transactional
    public CommentView add(BroadcastKind kind, Long broadcastId, Long memberId, String body) {
        requireBroadcast(kind, broadcastId);
        String validBody = validBody(body);
        Member author = entityManager.getReference(Member.class, memberId);
        Comment saved = comments.save(new Comment(kind, broadcastId, author, validBody));
        return view(saved, memberId);
    }

    /**
     * 작성자만 댓글을 고칠 수 있다.
     *
     * @throws CommentNotFoundException 없는 댓글
     * @throws NotCommentAuthorException 작성자가 아님
     * @throws InvalidCommentException 본문 규칙 위반
     */
    @Transactional
    public Target edit(Long commentId, Long memberId, String body) {
        Comment comment = authoredComment(commentId, memberId);
        comment.edit(validBody(body));
        return new Target(comment.getKind(), comment.getBroadcastId());
    }

    /**
     * 작성자만 댓글을 지울 수 있다.
     *
     * @throws CommentNotFoundException 없는 댓글
     * @throws NotCommentAuthorException 작성자가 아님
     */
    @Transactional
    public Target delete(Long commentId, Long memberId) {
        Comment comment = authoredComment(commentId, memberId);
        comments.delete(comment);
        return new Target(comment.getKind(), comment.getBroadcastId());
    }

    private Comment authoredComment(Long commentId, Long memberId) {
        Comment comment = comments.findById(commentId).orElseThrow(CommentNotFoundException::new);
        if (!comment.getAuthor().getId().equals(memberId)) {
            throw new NotCommentAuthorException();
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

    /** 방송이 없으면 {@link BroadcastNotFoundException}. 댓글을 읽거나 달기 전에 공통으로 거친다. */
    private void requireBroadcast(BroadcastKind kind, Long id) {
        boolean exists = switch (kind) {
            case RECORDED -> recorded.existsById(id);
            case LIVE -> live.existsById(id);
        };
        if (!exists) {
            throw new BroadcastNotFoundException();
        }
    }

    private static CommentView view(Comment c, Long viewerId) {
        return new CommentView(c.getId(), c.getAuthor().getNickname(), c.getBody(), c.getCreatedAt(),
                c.getUpdatedAt(), c.getAuthor().getId().equals(viewerId));
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
