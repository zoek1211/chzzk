package kr.devcourse.chzzk.live;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * 시그널링 중계. 라이브 방송마다 방(방송자 1명 + 시청자 여러 명)을 메모리에 두고
 * offer/answer/candidate를 맞는 상대에게만 전달한다. 미디어는 다루지 않는다.
 *
 * <pre>
 * 방송자 → 서버: {type:"offer"|"candidate", viewerId, ...}   → 해당 시청자에게 viewerId 없이 전달
 * 시청자 → 서버: {type:"answer"|"candidate", ...}            → 방송자에게 viewerId를 붙여 전달
 * 서버 → 방송자: {type:"viewer-joined"|"viewer-left", viewerId}
 * </pre>
 */
@Component
class SignalingHandler extends TextWebSocketHandler {

    static final String LIVE_ID = "liveId";
    static final String BROADCASTER = "broadcaster";

    private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);
    private static final Set<String> FROM_BROADCASTER = Set.of("offer", "candidate");
    private static final Set<String> FROM_VIEWER = Set.of("answer", "candidate");

    private final ObjectMapper json;
    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();

    private final LiveBroadcastService live;
    private final int viewerCapacity;

    SignalingHandler(
            ObjectMapper json,
            LiveBroadcastService live,
            @Value("${chzzk.live.viewer-capacity:5}") int viewerCapacity) {
        this.json = json;
        this.live = live;
        this.viewerCapacity = viewerCapacity;
    }

    /** 라이브 방송이 종료되면(종료 요청·연결 끊김 공통) 시청자 모두에게 알리고 연결을 닫는다. */
    @TransactionalEventListener(fallbackExecution = true)
    void onLiveEnded(LiveEnded ended) {
        Room room = rooms.remove(ended.liveId());
        if (room == null) {
            return;
        }
        String message = json.createObjectNode().put("type", "ended").toString();
        room.viewers.values().forEach(viewer -> {
            send(viewer, message);
            try {
                viewer.close(CloseStatus.NORMAL.withReason("방송 종료"));
            } catch (IOException | RuntimeException e) {
                log.debug("시청자 연결을 닫지 못했습니다", e);
            }
        });
    }

    /** 라이브 방송 하나의 방. 이후 단계(정원, 종료)에서 확장한다. */
    private static final class Room {
        volatile WebSocketSession broadcaster;
        final Map<String, WebSocketSession> viewers = new ConcurrentHashMap<>();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);
        Room room = rooms.computeIfAbsent(liveId(raw), id -> new Room());
        if (isBroadcaster(raw)) {
            synchronized (room) {
                if (room.broadcaster != null) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("이미 방송자가 접속해 있습니다"));
                    return;
                }
                room.broadcaster = session;
            }
            // 방송자보다 먼저 들어와 있던 시청자도 알려 준다.
            for (String viewerId : room.viewers.keySet()) {
                send(session, event("viewer-joined", viewerId));
            }
        } else {
            synchronized (room) {
                if (room.viewers.size() >= viewerCapacity) {
                    send(session, "{\"type\":\"full\"}");
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("시청 정원이 가득 찼습니다"));
                    return;
                }
                room.viewers.put(raw.getId(), session);
            }
            WebSocketSession broadcaster = room.broadcaster;
            if (broadcaster != null) {
                send(broadcaster, event("viewer-joined", raw.getId()));
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Room room = rooms.get(liveId(raw));
        if (room == null) {
            return;
        }
        try {
            JsonNode node = json.readTree(message.getPayload());
            if (!(node instanceof ObjectNode out) || !node.hasNonNull("type")) {
                return;
            }
            String type = node.get("type").asString();
            if (isBroadcaster(raw)) {
                if (!FROM_BROADCASTER.contains(type) || !node.hasNonNull("viewerId")) {
                    return;
                }
                WebSocketSession viewer = room.viewers.get(node.get("viewerId").asString());
                if (viewer != null) {
                    out.remove("viewerId");
                    send(viewer, out.toString());
                }
            } else {
                WebSocketSession broadcaster = room.broadcaster;
                if (FROM_VIEWER.contains(type) && broadcaster != null) {
                    out.put("viewerId", raw.getId());
                    send(broadcaster, out.toString());
                }
            }
        } catch (RuntimeException e) {
            log.debug("잘못된 시그널링 메시지를 무시합니다", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Room room = rooms.get(liveId(raw));
        if (room == null) {
            return;
        }
        if (isBroadcaster(raw)) {
            synchronized (room) {
                if (room.broadcaster != null && room.broadcaster.getId().equals(raw.getId())) {
                    room.broadcaster = null;
                    live.endByDisconnect(liveId(raw));
                }
            }
        } else if (room.viewers.remove(raw.getId()) != null) {
            WebSocketSession broadcaster = room.broadcaster;
            if (broadcaster != null) {
                send(broadcaster, event("viewer-left", raw.getId()));
            }
        }
    }

    private String event(String type, String viewerId) {
        ObjectNode node = json.createObjectNode();
        node.put("type", type);
        node.put("viewerId", viewerId);
        return node.toString();
    }

    private void send(WebSocketSession session, String payload) {
        try {
            session.sendMessage(new TextMessage(payload));
        } catch (IOException | RuntimeException e) {
            log.debug("시그널링 메시지를 보내지 못했습니다", e);
        }
    }

    private static Long liveId(WebSocketSession session) {
        return (Long) session.getAttributes().get(LIVE_ID);
    }

    private static boolean isBroadcaster(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get(BROADCASTER));
    }
}
