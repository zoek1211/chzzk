package kr.devcourse.chzzk.live;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 시그널링 WebSocket 경로.
 * 방송자: /ws/live/{id}/broadcaster (접속 뒤 첫 메시지 {type:"auth", key}로 인증), 시청자: /ws/live/{id}/viewer.
 * 시청자는 핸드셰이크 단계에서 거부한다(없는 방송 404, 종료된 방송 403).
 * 방송자는 송출 키를 주소에 싣지 않으므로 인증 실패(없는 방송·종료·틀린 키)를 접속 뒤 정책 위반으로 닫는다.
 */
@Configuration
@EnableWebSocket
class SignalingConfig implements WebSocketConfigurer {

    private static final Pattern PATH = Pattern.compile("/ws/live/(\\d+)/(broadcaster|viewer)");

    private final SignalingHandler handler;
    private final LiveBroadcastService live;

    SignalingConfig(SignalingHandler handler, LiveBroadcastService live) {
        this.handler = handler;
        this.live = live;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/live/*/broadcaster", "/ws/live/*/viewer")
                .addInterceptors(new Admission());
    }

    private final class Admission implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            Matcher path = PATH.matcher(request.getURI().getPath());
            if (!path.matches()) {
                return reject(response, HttpStatus.NOT_FOUND);
            }
            Long id = Long.valueOf(path.group(1));
            boolean broadcaster = path.group(2).equals("broadcaster");
            if (!broadcaster) {
                var found = live.find(id);
                if (found.isEmpty()) {
                    return reject(response, HttpStatus.NOT_FOUND);
                }
                if (!found.get().isOnAir()) {
                    return reject(response, HttpStatus.FORBIDDEN);
                }
            }
            // 방송자는 접속 뒤 첫 메시지(auth)로 인증하므로 핸드셰이크에서는 경로만 확인한다.
            attributes.put(SignalingHandler.Peer.ATTRIBUTE, new SignalingHandler.Peer(id,
                    broadcaster ? SignalingHandler.Role.BROADCASTER : SignalingHandler.Role.VIEWER));
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }

        private boolean reject(ServerHttpResponse response, HttpStatus status) {
            response.setStatusCode(status);
            return false;
        }
    }
}
