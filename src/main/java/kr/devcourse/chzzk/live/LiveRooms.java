package kr.devcourse.chzzk.live;

import java.util.Collection;
import tools.jackson.databind.node.ObjectNode;

/**
 * 라이브 방(방송자 1명 + 시청자 여러 명)의 공유 상태와 메시지 전달. 시그널링 핸들러는 자기 인스턴스의 WebSocket 연결만 쥐고,
 * "누가 방송자인가, 시청자가 몇 명인가, 이 메시지를 누구에게 보내는가"는 이 인터페이스 뒤에 둔다.
 * 단독 모드는 메모리({@link MemoryLiveRooms}), 클러스터 모드는 Redis({@link RedisLiveRooms}) 구현을 쓴다.
 * 연결은 세션 id로 가리킨다(시청자 id = 시청자 연결의 세션 id).
 */
interface LiveRooms {

    /** 이 인스턴스가 가진 연결에 메시지를 전하거나 연결을 닫는 쪽(시그널링 핸들러). */
    interface Local {

        /** 이 인스턴스의 그 세션에 메시지를 보낸다. 없으면 무시한다. */
        void deliver(String sessionId, ObjectNode message);

        /** 이 인스턴스의 그 라이브 연결 모두에 {@code ended}를 보내고 닫는다. */
        void endLocal(Long liveId);
    }

    /** 핸들러가 만들어질 때 한 번 불린다. 구현은 여기서부터 다른 인스턴스의 메시지를 받아도 된다. */
    void attach(Local local);

    /** 방송자로 들인다. 이미 다른 방송자가 있으면 false. */
    boolean admitBroadcaster(Long liveId, String sessionId);

    /** 방송자 연결이 끊겼다. 단독 모드는 방송을 바로 종료하고, 클러스터 모드는 접속 기록만 지운다. */
    void broadcasterGone(Long liveId, String sessionId);

    /** 시청자로 들인다. 정원이 가득 찼으면 false. */
    boolean admitViewer(Long liveId, String viewerId);

    /** 시청자 연결이 끊겼다. 명단에 있던 시청자였으면 true. */
    boolean viewerGone(Long liveId, String viewerId);

    /** 지금 방에 있는 시청자 id. */
    Collection<String> viewerIds(Long liveId);

    void toBroadcaster(Long liveId, ObjectNode message);

    void toViewer(Long liveId, String viewerId, ObjectNode message);

    /** 라이브가 시작되었다: 방송자 접속 유예를 재기 시작한다. */
    void liveStarted(Long liveId);

    /** 라이브가 종료되었다: 방 상태를 지우고 그 라이브의 모든 연결(모든 인스턴스)에 종료를 알린다. */
    void liveEnded(Long liveId);

    /** 이 인스턴스가 정상 종료할 때 자기 연결을 "재접속하라"로 닫고 방송은 그대로 두는가(클러스터 모드). */
    default boolean keepsLivesOnShutdown() {
        return false;
    }

    /** 정상 종료 직전, 핸들러가 자기 연결을 정리한 뒤 불린다. */
    default void stopping() {
    }
}
