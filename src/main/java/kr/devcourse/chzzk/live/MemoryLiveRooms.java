package kr.devcourse.chzzk.live;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** 단독 모드의 방 상태: 인스턴스 메모리. 방송자 연결이 끊기면 라이브를 바로 종료하고, 시작 직후 접속 유예도 메모리의 방으로 검사한다. */
@Component
@ConditionalOnProperty(name = "chzzk.cluster.enabled", havingValue = "false", matchIfMissing = true)
class MemoryLiveRooms implements LiveRooms {

    private static final Logger log = LoggerFactory.getLogger(MemoryLiveRooms.class);

    private final LiveBroadcastService live;
    private final int viewerCapacity;
    private final Duration broadcasterJoinTimeout;
    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "live-join-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Local local;

    MemoryLiveRooms(
            LiveBroadcastService live,
            @Value("${chzzk.live.viewer-capacity:5}") int viewerCapacity,
            @Value("${chzzk.live.broadcaster-join-timeout:30s}") Duration broadcasterJoinTimeout) {
        this.live = live;
        this.viewerCapacity = viewerCapacity;
        this.broadcasterJoinTimeout = broadcasterJoinTimeout;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class Room {
        String broadcaster;
        final Set<String> viewers = ConcurrentHashMap.newKeySet();
    }

    @Override
    public void attach(Local local) {
        this.local = local;
    }

    @Override
    public boolean admitBroadcaster(Long liveId, String sessionId) {
        Room room = rooms.computeIfAbsent(liveId, id -> new Room());
        synchronized (room) {
            if (room.broadcaster != null) {
                return false;
            }
            room.broadcaster = sessionId;
            return true;
        }
    }

    @Override
    public void broadcasterGone(Long liveId, String sessionId) {
        Room room = rooms.get(liveId);
        if (room == null) {
            return;
        }
        synchronized (room) {
            if (sessionId.equals(room.broadcaster)) {
                room.broadcaster = null;
                live.endByDisconnect(liveId);
            }
        }
    }

    @Override
    public boolean admitViewer(Long liveId, String viewerId) {
        Room room = rooms.computeIfAbsent(liveId, id -> new Room());
        synchronized (room) {
            if (room.viewers.size() >= viewerCapacity) {
                return false;
            }
            room.viewers.add(viewerId);
            return true;
        }
    }

    @Override
    public boolean viewerGone(Long liveId, String viewerId) {
        Room room = rooms.get(liveId);
        return room != null && room.viewers.remove(viewerId);
    }

    @Override
    public Collection<String> viewerIds(Long liveId) {
        Room room = rooms.get(liveId);
        return room == null ? List.of() : List.copyOf(room.viewers);
    }

    @Override
    public void toBroadcaster(Long liveId, ObjectNode message) {
        Room room = rooms.get(liveId);
        String broadcaster = room == null ? null : room.broadcaster;
        if (broadcaster != null) {
            local.deliver(broadcaster, message);
        }
    }

    @Override
    public void toViewer(Long liveId, String viewerId, ObjectNode message) {
        Room room = rooms.get(liveId);
        if (room != null && room.viewers.contains(viewerId)) {
            local.deliver(viewerId, message);
        }
    }

    /** 유예 시간 안에 방송자가 접속하지 않을 때 종료로 바꿀 검사를 예약한다. */
    @Override
    public void liveStarted(Long liveId) {
        scheduler.schedule(() -> endIfNoBroadcaster(liveId), broadcasterJoinTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void endIfNoBroadcaster(Long liveId) {
        try {
            Room room = rooms.get(liveId);
            if (room == null || room.broadcaster == null) {
                live.endByDisconnect(liveId);
            }
        } catch (RuntimeException e) {
            log.warn("방송자 접속 유예 시간 검사에 실패했습니다", e);
        }
    }

    @Override
    public void liveEnded(Long liveId) {
        rooms.remove(liveId);
        local.endLocal(liveId);
    }
}
