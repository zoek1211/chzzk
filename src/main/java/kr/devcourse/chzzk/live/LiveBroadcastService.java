package kr.devcourse.chzzk.live;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import kr.devcourse.chzzk.member.Member;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveBroadcastService {

    private final LiveBroadcastRepository broadcasts;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    public LiveBroadcastService(LiveBroadcastRepository broadcasts, ApplicationEventPublisher events,
                                EntityManager entityManager) {
        this.broadcasts = broadcasts;
        this.events = events;
        this.entityManager = entityManager;
    }

    /**
     * 회원이 새 라이브 방송을 방송 중으로 시작한다. 시작한 회원이 소유자가 된다.
     *
     * @throws LiveConflictException 그 회원이 이미 방송 중인 라이브를 가지고 있음
     */
    public synchronized StartedLive start(String title, Long ownerId) {
        String validTitle = LiveBroadcast.validTitle(title);
        if (broadcasts.existsByOwnerIdAndStatus(ownerId, LiveStatus.ON_AIR)) {
            throw new LiveConflictException("이미 방송 중인 라이브가 있습니다. 종료한 뒤 다시 시작해 주세요.");
        }
        LiveBroadcast saved = broadcasts.save(new LiveBroadcast(validTitle, entityManager.getReference(Member.class, ownerId)));
        events.publishEvent(new LiveStarted(saved.getId()));
        return new StartedLive(saved.getId());
    }

    /** 방송 중이 먼저, 그다음 종료. 각 그룹은 최신순. */
    public List<LiveBroadcast> list() {
        return broadcasts.findAllInListOrder();
    }

    /**
     * 소유자가 라이브 방송을 종료한다. 이미 종료면 아무것도 바꾸지 않고 성공한다.
     *
     * @throws LiveNotFoundException 없는 라이브 방송
     * @throws NotLiveOwnerException 소유자가 아님(소유자 없는 기존 라이브 포함, 방송 상태는 그대로)
     */
    @Transactional
    public void end(Long id, Long memberId) {
        endAndAnnounce(findOwned(id, memberId));
    }

    /**
     * 소유자가 종료된 라이브 방송을 삭제한다.
     *
     * @throws LiveNotFoundException 없는 라이브 방송
     * @throws NotLiveOwnerException 소유자가 아님
     * @throws LiveConflictException 아직 방송 중
     */
    @Transactional
    public void delete(Long id, Long memberId) {
        LiveBroadcast broadcast = findOwned(id, memberId);
        if (broadcast.isOnAir()) {
            throw new LiveConflictException("방송 중인 라이브는 삭제할 수 없습니다. 먼저 종료해 주세요.");
        }
        broadcasts.delete(broadcast);
        events.publishEvent(new LiveDeleted(id));
    }

    /** 방송을 찾아 소유자인지 확인한다. 없으면 {@link LiveNotFoundException}, 소유자가 아니면 {@link NotLiveOwnerException}. */
    private LiveBroadcast findOwned(Long id, Long memberId) {
        LiveBroadcast broadcast = broadcasts.findById(id).orElseThrow(LiveNotFoundException::new);
        if (!broadcast.isOwnedBy(memberId)) {
            throw new NotLiveOwnerException();
        }
        return broadcast;
    }

    /** 방송자 연결이 끊겨 종료한다. 없거나 이미 종료면 아무 일도 없다. 내부(시그널링)용. */
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

    public Optional<LiveBroadcast> find(Long id) {
        return broadcasts.findById(id);
    }

    public record StartedLive(Long id) {
    }
}
