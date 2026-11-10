package kr.devcourse.chzzk.comment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.devcourse.chzzk.member.Member;

/** 댓글: 대상 방송(종류+id), 작성자(회원), 본문(1~500자), 작성 시각. */
@Entity
@Table(name = "comment", indexes = @Index(columnList = "kind, broadcastId"))
class Comment {

    static final int MAX_BODY_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BroadcastKind kind;

    @Column(nullable = false)
    private Long broadcastId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private Member author;

    @Column(nullable = false, length = MAX_BODY_LENGTH)
    private String body;

    @Column(nullable = false)
    private Instant createdAt;

    protected Comment() {
    }

    Comment(BroadcastKind kind, Long broadcastId, Member author, String body) {
        this.kind = kind;
        this.broadcastId = broadcastId;
        this.author = author;
        this.body = body;
        this.createdAt = Instant.now();
    }

    Long getId() {
        return id;
    }

    Member getAuthor() {
        return author;
    }

    String getBody() {
        return body;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
