package kr.devcourse.chzzk.live;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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
 * 시그널링 중계. 이 인스턴스에 붙은 WebSocket 연결을 쥐고, 라이브 방송마다 방(room: 방송자 1명 + 시청자 여러 명)의 상태와
 * 상대에게 메시지를 전하는 일은 {@link LiveRooms}에 맡긴다(단독 모드는 메모리, 클러스터 모드는 Redis).
 * offer/answer/candidate를 맞는 상대에게만 전달한다. 미디어는 다루지 않는다.
 *
 * <pre>
 * 서버 → 방송자: {type:"authenticated"}                      접속 직후 방송자로 받아들였다는 확인(핸드셰이크에서 소유자 세션을 이미 검사함)
 * 방송자 → 서버: {type:"offer"|"candidate", viewerId, ...}   → 해당 시청자에게 viewerId 없이 전달
 * 시청자 → 서버: {type:"answer"|"candidate", ...}            → 방송자에게 viewerId를 붙여 전달
 * 서버 → 방송자: {type:"viewer-joined"|"viewer-left", viewerId}
 * 서버 → 시청자: {type:"full"|"ended"}   ("ended"는 방송자에게도 간다)
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
    /** 인스턴스가 사라지니 다시 접속하라는 닫기 코드(1012, Service Restart). 방송은 끝나지 않았다. */
    static final CloseStatus RECONNECT = CloseStatus.SERVICE_RESTARTED.withReason("서버 교체 중, 다시 접속해 주세요");

    private final ObjectMapper json;
    private final LiveRooms rooms;
    /** 이 인스턴스의 모든 연결(방송자·시청자), 세션 id로. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 이 인스턴스에서 방송자로 받아들여진 연결의 세션 id. */
    private final Set<String> broadcasters = ConcurrentHashMap.newKeySet();

    SignalingHandler(ObjectMapper json, LiveRooms rooms) {
        this.json = json;
        this.rooms = rooms;
        rooms.attach(new Local());
    }

    /** 이 인스턴스의 연결에 닿는 창구. 방 구현이 다른 인스턴스에서 온 메시지를 여기로 넘긴다. */
    private final class Local implements LiveRooms.Local {

        @Override
        public void deliver(String sessionId, ObjectNode message) {
            WebSocketSession session = sessions.get(sessionId);
            if (session != null) {
                send(session, message);
            }
        }

        @Override
        public void endLocal(Long liveId) {
            ObjectNode ended = message("ended");
            for (WebSocketSession session : List.copyOf(sessions.values())) {
                if (Peer.of(session).liveId().equals(liveId)) {
                    sessions.remove(session.getId());
                    broadcasters.remove(session.getId());
                    send(session, ended);
                    close(session, CloseStatus.NORMAL.withReason("방송 종료"));
                }
            }
        }
    }

    /** 라이브가 시작되면 방송자 접속 유예를 재기 시작한다. */
    @TransactionalEventListener(fallbackExecution = true)
    void onLiveStarted(LiveStarted started) {
        rooms.liveStarted(started.liveId());
    }

    /** 라이브 방송이 종료되면(종료 요청·연결 끊김·접속 유예 초과 공통) 시청자 모두에게 알리고 연결을 닫는다. */
    @TransactionalEventListener(fallbackExecution = true)
    void onLiveEnded(LiveEnded ended) {
        rooms.liveEnded(ended.liveId());
    }

    /**
     * 클러스터 모드에서 인스턴스가 정상 종료할 때(웹 서버가 내려가기 전) 자기 연결을 "재접속하라"로 닫는다. 방송은 종료하지 않는다.
     * 단독 모드는 기존대로 서버가 연결을 닫을 때의 처리에 맡긴다.
     */
    @EventListener
    void onContextClosed(ContextClosedEvent event) {
        if (!rooms.keepsLivesOnShutdown()) {
            return;
        }
        for (WebSocketSession session : List.copyOf(sessions.values())) {
            try {
                release(session);
            } catch (RuntimeException e) {
                log.debug("종료 중 연결 정리에 실패했습니다", e);
            }
            close(session, RECONNECT);
        }
        rooms.stopping();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        Peer peer = Peer.of(raw);
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);
        sessions.put(raw.getId(), session);
        if (peer.role() == Role.VIEWER) {
            admitViewer(raw, session, peer.liveId());
        } else {
            admitBroadcaster(raw, session, peer.liveId());
        }
    }

    private void admitViewer(WebSocketSession raw, WebSocketSession session, Long liveId) {
        if (!rooms.admitViewer(liveId, raw.getId())) {
            sessions.remove(raw.getId());
            send(session, message("full"));
            close(session, CloseStatus.POLICY_VIOLATION.withReason("시청 정원이 가득 찼습니다"));
            return;
        }
        rooms.toBroadcaster(liveId, event("viewer-joined", raw.getId()));
    }

    /** 핸드셰이크가 소유자 세션임을 확인했으므로, 아직 방송자가 없으면 방송자로 들인다. */
    private void admitBroadcaster(WebSocketSession raw, WebSocketSession session, Long liveId) {
        if (!rooms.admitBroadcaster(liveId, raw.getId())) {
            sessions.remove(raw.getId());
            close(raw, CloseStatus.POLICY_VIOLATION.withReason("이미 방송자가 접속해 있습니다"));
            return;
        }
        broadcasters.add(raw.getId());
        send(session, message("authenticated"));
        // 방송자보다 먼저 들어와 있던 시청자도 알려 준다.
        rooms.viewerIds(liveId).forEach(viewerId -> send(session, event("viewer-joined", viewerId)));
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
        if (!broadcasters.contains(raw.getId())) {
            return; // 방송자로 받아들여지지 않은 연결(이미 방송자가 있어 닫히는 중)의 메시지는 무시한다.
        }
        if (!FROM_BROADCASTER.contains(type) || !body.hasNonNull("viewerId")) {
            return;
        }
        String viewerId = body.get("viewerId").asString();
        body.remove("viewerId");
        rooms.toViewer(liveId, viewerId, body);
    }

    private void handleFromViewer(WebSocketSession raw, Long liveId, String type, ObjectNode body) {
        if (FROM_VIEWER.contains(type)) {
            body.put("viewerId", raw.getId());
            rooms.toBroadcaster(liveId, body);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        release(raw);
    }

    /** 이 인스턴스가 쥔 연결 하나를 정리하고 방 상태에서 뺀다. 이미 정리된 연결이면 아무 일도 없다. */
    private void release(WebSocketSession session) {
        if (sessions.remove(session.getId()) == null) {
            return;
        }
        Peer peer = Peer.of(session);
        if (peer.role() == Role.BROADCASTER) {
            if (broadcasters.remove(session.getId())) {
                rooms.broadcasterGone(peer.liveId(), session.getId());
            }
        } else if (rooms.viewerGone(peer.liveId(), session.getId())) {
            rooms.toBroadcaster(peer.liveId(), event("viewer-left", session.getId()));
        }
    }

    private ObjectNode message(String type) {
        return json.createObjectNode().put("type", type);
    }

    private ObjectNode event(String type, String viewerId) {
        return message(type).put("viewerId", viewerId);
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
