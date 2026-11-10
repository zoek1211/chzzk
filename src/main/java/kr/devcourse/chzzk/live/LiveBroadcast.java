package kr.devcourse.chzzk.live;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

/** 라이브 방송: 방송자가 지금 내보내는 방송. 송출 키는 해시로만 보관한다. */
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

    @Column(nullable = false, length = 64)
    private String broadcastKeyHash;

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

    LiveBroadcast(String title, String broadcastKeyHash) {
        this.title = title;
        this.status = LiveStatus.ON_AIR;
        this.broadcastKeyHash = broadcastKeyHash;
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

    boolean matchesKeyHash(String hash) {
        return broadcastKeyHash.equals(hash);
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
