package kr.devcourse.chzzk.live;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 시그널링 중계. 라이브 방송마다 방(room: 방송자 1명 + 시청자 여러 명)을 메모리에 두고
 * offer/answer/candidate를 맞는 상대에게만 전달한다. 미디어는 다루지 않는다.
 *
 * <pre>
 * 방송자 → 서버: {type:"auth", key}                          접속 직후 첫 메시지. 통과해야 방송자가 된다
 * 서버 → 방송자: {type:"authenticated"}                      인증 확인
 * 방송자 → 서버: {type:"offer"|"candidate", viewerId, ...}   → 해당 시청자에게 viewerId 없이 전달
 * 시청자 → 서버: {type:"answer"|"candidate", ...}            → 방송자에게 viewerId를 붙여 전달
 * 서버 → 방송자: {type:"viewer-joined"|"viewer-left", viewerId}
 * 서버 → 시청자: {type:"full"|"ended"}
 * </pre>
 */
@Component
class SignalingHandler extends TextWebSocketHandler {

    /** 접속한 쪽의 역할. */
    enum Role {
        BROADCASTER, VIEWER
    }

    /** 핸드셰이크에서 정해져 세션 속성에 담기는 접속자 정보. */
    record Peer(Long liveId, Role role) {

        static final String ATTRIBUTE = "peer";

        static Peer of(WebSocketSession session) {
            return (Peer) session.getAttributes().get(ATTRIBUTE);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);
    private static final Set<String> FROM_BROADCASTER = Set.of("offer", "candidate");
    private static final Set<String> FROM_VIEWER = Set.of("answer", "candidate");

    private final ObjectMapper json;
    private final LiveBroadcastService live;
    private final int viewerCapacity;
    private final Duration broadcasterJoinTimeout;
    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "live-join-timeout");
        thread.setDaemon(true);
        return thread;
    });

    SignalingHandler(
            ObjectMapper json,
            LiveBroadcastService live,
            @Value("${chzzk.live.viewer-capacity:5}") int viewerCapacity,
            @Value("${chzzk.live.broadcaster-join-timeout:30s}") Duration broadcasterJoinTimeout) {
        this.json = json;
        this.live = live;
        this.viewerCapacity = viewerCapacity;
        this.broadcasterJoinTimeout = broadcasterJoinTimeout;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** 라이브가 시작되면, 유예 시간 안에 방송자가 인증하지 않을 때 종료로 바꿀 검사를 예약한다. */
    @TransactionalEventListener(fallbackExecution = true)
    void onLiveStarted(LiveStarted started) {
        scheduler.schedule(() -> endIfNoBroadcaster(started.liveId()),
                broadcasterJoinTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

    /** 라이브 방송이 종료되면(종료 요청·연결 끊김·접속 유예 초과 공통) 시청자 모두에게 알리고 연결을 닫는다. */
    @TransactionalEventListener(fallbackExecution = true)
    void onLiveEnded(LiveEnded ended) {
        Room room = rooms.remove(ended.liveId());
        if (room == null) {
            return;
        }
        ObjectNode message = message("ended");
        room.viewers.values().forEach(viewer -> {
            send(viewer, message);
            close(viewer, CloseStatus.NORMAL.withReason("방송 종료"));
        });
    }

    /** 라이브 방송 하나의 방. 인증한 방송자는 한 명뿐이다. */
    private static final class Room {
        volatile WebSocketSession broadcaster;
        final Map<String, WebSocketSession> viewers = new ConcurrentHashMap<>();

        boolean isBroadcaster(WebSocketSession session) {
            WebSocketSession current = broadcaster;
            return current != null && current.getId().equals(session.getId());
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        Peer peer = Peer.of(raw);
        if (peer.role() == Role.VIEWER) {
            admitViewer(raw, peer.liveId());
        }
        // 방송자는 첫 메시지(auth)가 통과해야 방으로 들어온다.
    }

    private void admitViewer(WebSocketSession raw, Long liveId) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);
        Room room = rooms.computeIfAbsent(liveId, id -> new Room());
        synchronized (room) {
            if (room.viewers.size() >= viewerCapacity) {
                send(session, message("full"));
                close(session, CloseStatus.POLICY_VIOLATION.withReason("시청 정원이 가득 찼습니다"));
                return;
            }
            room.viewers.put(raw.getId(), session);
        }
        sendToBroadcaster(room, event("viewer-joined", raw.getId()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Peer peer = Peer.of(raw);
        try {
            JsonNode node = json.readTree(message.getPayload());
            if (!(node instanceof ObjectNode body) || !node.hasNonNull("type")) {
                return;
            }
            String type = node.get("type").asString();
            switch (peer.role()) {
                case BROADCASTER -> handleFromBroadcaster(raw, peer.liveId(), type, body);
                case VIEWER -> handleFromViewer(raw, peer.liveId(), type, body);
            }
        } catch (RuntimeException e) {
            log.debug("잘못된 시그널링 메시지를 무시합니다", e);
        }
    }

    private void handleFromBroadcaster(WebSocketSession raw, Long liveId, String type, ObjectNode body) {
        Room room = rooms.get(liveId);
        if (room == null || !room.isBroadcaster(raw)) {
            if (type.equals("auth")) {
                admitBroadcaster(raw, liveId, body.path("key").asString(null));
            } else {
                close(raw, CloseStatus.POLICY_VIOLATION.withReason("먼저 송출 키로 인증해야 합니다"));
            }
            return;
        }
        if (!FROM_BROADCASTER.contains(type) || !body.hasNonNull("viewerId")) {
            return;
        }
        WebSocketSession viewer = room.viewers.get(body.get("viewerId").asString());
        if (viewer != null) {
            body.remove("viewerId");
            send(viewer, body);
        }
    }

    private void handleFromViewer(WebSocketSession raw, Long liveId, String type, ObjectNode body) {
        Room room = rooms.get(liveId);
        if (room != null && FROM_VIEWER.contains(type)) {
            body.put("viewerId", raw.getId());
            sendToBroadcaster(room, body);
        }
    }

    /** 송출 키가 맞는 방송 중 라이브이고 아직 방송자가 없을 때만 방송자로 들인다. */
    private void admitBroadcaster(WebSocketSession raw, Long liveId, String key) {
        if (!live.canBroadcast(liveId, key)) {
            close(raw, CloseStatus.POLICY_VIOLATION.withReason("송출할 수 없는 방송입니다"));
            return;
        }
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);
        Room room = rooms.computeIfAbsent(liveId, id -> new Room());
        synchronized (room) {
            if (room.broadcaster != null) {
                close(raw, CloseStatus.POLICY_VIOLATION.withReason("이미 방송자가 접속해 있습니다"));
                return;
            }
            room.broadcaster = session;
        }
        send(session, message("authenticated"));
        // 방송자보다 먼저 들어와 있던 시청자도 알려 준다.
        room.viewers.keySet().forEach(viewerId -> send(session, event("viewer-joined", viewerId)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Peer peer = Peer.of(raw);
        Room room = rooms.get(peer.liveId());
        if (room == null) {
            return;
        }
        if (peer.role() == Role.BROADCASTER) {
            synchronized (room) {
                if (room.isBroadcaster(raw)) {
                    room.broadcaster = null;
                    live.endByDisconnect(peer.liveId());
                }
            }
        } else if (room.viewers.remove(raw.getId()) != null) {
            sendToBroadcaster(room, event("viewer-left", raw.getId()));
        }
    }

    private ObjectNode message(String type) {
        return json.createObjectNode().put("type", type);
    }

    private ObjectNode event(String type, String viewerId) {
        return message(type).put("viewerId", viewerId);
    }

    private void sendToBroadcaster(Room room, ObjectNode message) {
        WebSocketSession broadcaster = room.broadcaster;
        if (broadcaster != null) {
            send(broadcaster, message);
        }
    }

    private void send(WebSocketSession session, ObjectNode message) {
        try {
            session.sendMessage(new TextMessage(message.toString()));
        } catch (IOException | RuntimeException e) {
            log.debug("시그널링 메시지를 보내지 못했습니다", e);
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException | RuntimeException e) {
            log.debug("연결을 닫지 못했습니다", e);
        }
    }
}
