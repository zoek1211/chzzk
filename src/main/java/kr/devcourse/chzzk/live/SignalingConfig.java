package kr.devcourse.chzzk.live;

import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 시그널링 WebSocket 경로.
 * 방송자: /ws/live/{id}/broadcaster, 시청자: /ws/live/{id}/viewer.
 * 둘 다 핸드셰이크 단계에서 거부한다(없는 방송 404, 종료된 방송 403).
 * 방송자는 추가로 HTTP 세션의 로그인 회원이 그 라이브의 소유자여야 한다(비로그인 401, 다른 회원·소유자 없는 라이브 403).
 * 세션은 Spring Security가 HTTP 세션에 저장한 SecurityContext에서 읽는다.
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
                Long memberId = sessionMemberId(request);
                if (memberId == null) {
                    return reject(response, HttpStatus.UNAUTHORIZED);
                }
                if (!found.get().isOwnedBy(memberId)) {
                    return reject(response, HttpStatus.FORBIDDEN);
                }
            }
            if (!found.get().isOnAir()) {
                return reject(response, HttpStatus.FORBIDDEN);
            }
            attributes.put(SignalingHandler.Peer.ATTRIBUTE, new SignalingHandler.Peer(id,
                    broadcaster ? SignalingHandler.Role.BROADCASTER : SignalingHandler.Role.VIEWER));
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }

        /** 요청의 HTTP 세션에 로그인한 회원이 있으면 그 id, 없으면 null. 세션을 새로 만들지 않는다. */
        private Long sessionMemberId(ServerHttpRequest request) {
            if (!(request instanceof ServletServerHttpRequest servlet)) {
                return null;
            }
            HttpSession session = servlet.getServletRequest().getSession(false);
            if (session == null) {
                return null;
            }
            Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context instanceof SecurityContext security
                    && security.getAuthentication() != null
                    && security.getAuthentication().getPrincipal() instanceof MemberPrincipal member) {
                return member.memberId();
            }
            return null;
        }

        private boolean reject(ServerHttpResponse response, HttpStatus status) {
            response.setStatusCode(status);
            return false;
        }
    }
}
