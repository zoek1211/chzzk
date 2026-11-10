package kr.devcourse.chzzk.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveBroadcastService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LiveBroadcastRepository broadcasts;
    private final ApplicationEventPublisher events;

    public LiveBroadcastService(LiveBroadcastRepository broadcasts, ApplicationEventPublisher events) {
        this.broadcasts = broadcasts;
        this.events = events;
    }

    /** 새 라이브 방송을 방송 중으로 시작한다. 송출 키 원문은 이 결과로만 얻을 수 있다. */
    public StartedLive start(String title) {
        String broadcastKey = newBroadcastKey();
        LiveBroadcast saved = broadcasts.save(
                new LiveBroadcast(LiveBroadcast.validTitle(title), hash(broadcastKey)));
        events.publishEvent(new LiveStarted(saved.getId()));
        return new StartedLive(saved.getId(), broadcastKey);
    }

    /** 방송 중이 먼저, 그다음 종료. 각 그룹은 최신순. */
    public List<LiveBroadcast> list() {
        return broadcasts.findAllInListOrder();
    }

    /**
     * 송출 키로 라이브 방송을 종료한다. 이미 종료면 아무것도 바꾸지 않고 성공한다.
     *
     * @throws LiveNotFoundException 없는 라이브 방송
     * @throws InvalidBroadcastKeyException 송출 키가 없거나 틀림(방송 상태는 그대로)
     */
    @Transactional
    public void end(Long id, String broadcastKey) {
        LiveBroadcast broadcast = broadcasts.findById(id).orElseThrow(LiveNotFoundException::new);
        if (!isValidKey(broadcast, broadcastKey)) {
            throw new InvalidBroadcastKeyException();
        }
        endAndAnnounce(broadcast);
    }

    /** 송출 키가 방송 중인 그 라이브 방송의 것인지. 없거나 종료된 방송이면 false. (송출 접속 검증용, 조회 한 번) */
    @Transactional(readOnly = true)
    public boolean canBroadcast(Long id, String broadcastKey) {
        return broadcasts.findById(id).map(b -> b.isOnAir() && isValidKey(b, broadcastKey)).orElse(false);
    }

    /** 방송자 연결이 끊겨 송출 키 없이 종료한다. 없거나 이미 종료면 아무 일도 없다. 내부(시그널링)용. */
    @Transactional
    public void endByDisconnect(Long id) {
        broadcasts.findById(id).ifPresent(this::endAndAnnounce);
    }

    /** 서비스가 다시 시작될 때 남아 있는 방송 중 라이브 방송을 모두 종료로 바꾼다. */
    @Transactional
    public int endAllOnAir() {
        List<LiveBroadcast> onAir = broadcasts.findAllByStatus(LiveStatus.ON_AIR);
        onAir.forEach(LiveBroadcast::end);
        return onAir.size();
    }

    /** 방송 중이었다면 종료로 바꾸고, 시청자에게 알릴 수 있도록 종료 이벤트를 낸다. */
    private void endAndAnnounce(LiveBroadcast broadcast) {
        if (broadcast.end()) {
            events.publishEvent(new LiveEnded(broadcast.getId()));
        }
    }

    private static boolean isValidKey(LiveBroadcast broadcast, String broadcastKey) {
        return broadcastKey != null && !broadcastKey.isBlank() && broadcast.matchesKeyHash(hash(broadcastKey));
    }

    public Optional<LiveBroadcast> find(Long id) {
        return broadcasts.findById(id);
    }

    private static String newBroadcastKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String broadcastKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(broadcastKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record StartedLive(Long id, String broadcastKey) {
    }
}
