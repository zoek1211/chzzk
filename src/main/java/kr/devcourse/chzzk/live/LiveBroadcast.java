package kr.devcourse.chzzk.live;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/** 라이브 방송: 방송자가 지금 내보내는 방송. 소유자(회원 id)는 회원 기능 이전에 만들어진 방송에서는 없다. */
@Entity
public class LiveBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LiveStatus status;

    /** 소유자 회원의 id. 회원 기능 이전에 만들어진 라이브는 null(누구도 관리할 수 없다). */
    private Long ownerId;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    private static final int MAX_TITLE_LENGTH = 200;

    protected LiveBroadcast() {
    }

    /** 라이브 제목 규칙: 공백만으로 이루어질 수 없고 최대 200자. 앞뒤 공백은 지운다. */
    static String validTitle(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidLiveStartException("제목을 입력해 주세요.");
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new InvalidLiveStartException("제목은 " + MAX_TITLE_LENGTH + "자 이하로 입력해 주세요.");
        }
        return trimmed;
    }

    LiveBroadcast(String title, Long ownerId) {
        this.title = title;
        this.status = LiveStatus.ON_AIR;
        this.ownerId = ownerId;
        this.startedAt = Instant.now();
    }

    /** 방송 중에서 종료로만 바뀐다. 이미 종료면 아무 일도 없다. 실제로 바뀌었으면 true. */
    boolean end() {
        if (status == LiveStatus.ON_AIR) {
            status = LiveStatus.ENDED;
            endedAt = Instant.now();
            return true;
        }
        return false;
    }

    /** 이 회원이 소유자인가. 소유자가 없는 라이브는 누구에게도 false. */
    public boolean isOwnedBy(Long memberId) {
        return ownerId != null && ownerId.equals(memberId);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public LiveStatus getStatus() {
        return status;
    }

    public boolean isOnAir() {
        return status == LiveStatus.ON_AIR;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
