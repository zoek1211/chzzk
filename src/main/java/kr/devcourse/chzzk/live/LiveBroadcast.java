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

    protected LiveBroadcast() {
    }

    LiveBroadcast(String title, String broadcastKeyHash) {
        this.title = title;
        this.status = LiveStatus.ON_AIR;
        this.broadcastKeyHash = broadcastKeyHash;
        this.startedAt = Instant.now();
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

    boolean matchesKeyHash(String hash) {
        return java.security.MessageDigest.isEqual(
                broadcastKeyHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
