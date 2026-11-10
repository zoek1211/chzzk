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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 시그널링 WebSocket 경로.
 * 방송자: /ws/live/{id}/broadcaster?key=송출키, 시청자: /ws/live/{id}/viewer.
 * 핸드셰이크 단계에서 거부한다(없는 방송 404, 키가 틀리거나 종료된 방송 403).
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
            var found = live.find(id);
            if (found.isEmpty()) {
                return reject(response, HttpStatus.NOT_FOUND);
            }
            if (broadcaster) {
                String key = UriComponentsBuilder.fromUri(request.getURI()).build()
                        .getQueryParams().getFirst("key");
                if (!found.get().isOnAir() || !live.verifyBroadcastKey(id, key)) {
                    return reject(response, HttpStatus.FORBIDDEN);
                }
            } else if (!found.get().isOnAir()) {
                return reject(response, HttpStatus.FORBIDDEN);
            }
            attributes.put(SignalingHandler.LIVE_ID, id);
            attributes.put(SignalingHandler.BROADCASTER, broadcaster);
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
