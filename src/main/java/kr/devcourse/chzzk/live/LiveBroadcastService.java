package kr.devcourse.chzzk.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import kr.devcourse.chzzk.broadcast.RecordedBroadcast;
import org.springframework.stereotype.Service;

@Service
public class LiveBroadcastService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LiveBroadcastRepository broadcasts;

    public LiveBroadcastService(LiveBroadcastRepository broadcasts) {
        this.broadcasts = broadcasts;
    }

    /** 새 라이브 방송을 방송 중으로 시작한다. 송출 키 원문은 이 결과로만 얻을 수 있다. */
    public StartedLive start(String title) {
        String broadcastKey = newBroadcastKey();
        LiveBroadcast saved = broadcasts.save(
                new LiveBroadcast(RecordedBroadcast.validTitle(title), hash(broadcastKey)));
        return new StartedLive(saved.getId(), broadcastKey);
    }

    /** 방송 중이 먼저, 그다음 종료. 각 그룹은 최신순. */
    public List<LiveBroadcast> list() {
        List<LiveBroadcast> all = new ArrayList<>(
                broadcasts.findAllByStatusOrderByStartedAtDescIdDesc(LiveStatus.ON_AIR));
        all.addAll(broadcasts.findAllByStatusOrderByStartedAtDescIdDesc(LiveStatus.ENDED));
        return all;
    }

    public Optional<LiveBroadcast> find(Long id) {
        return broadcasts.findById(id);
    }

    /** 방송 중인 라이브 방송이고 송출 키가 맞으면 true. */
    public boolean acceptsBroadcaster(Long id, String broadcastKey) {
        return broadcastKey != null && broadcasts.findById(id)
                .filter(LiveBroadcast::isOnAir)
                .filter(b -> b.matchesKeyHash(hash(broadcastKey)))
                .isPresent();
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
