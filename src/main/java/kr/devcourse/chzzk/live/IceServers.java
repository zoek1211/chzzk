package kr.devcourse.chzzk.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import tools.jackson.databind.ObjectMapper;

/** 송출·시청 화면에 내려줄 ICE 서버 목록. STUN은 기본 공개 서버, TURN은 설정했을 때만 포함한다. */
@ControllerAdvice(assignableTypes = LiveBroadcastController.class)
class IceServers {

    private final String json;

    IceServers(ObjectMapper mapper,
               @Value("${chzzk.ice.stun-url}") String stunUrl,
               @Value("${chzzk.ice.turn-url:}") String turnUrl,
               @Value("${chzzk.ice.turn-username:}") String turnUsername,
               @Value("${chzzk.ice.turn-credential:}") String turnCredential) {
        List<Map<String, Object>> servers = new ArrayList<>();
        servers.add(Map.of("urls", stunUrl));
        if (StringUtils.hasText(turnUrl)) {
            servers.add(Map.of("urls", turnUrl, "username", turnUsername, "credential", turnCredential));
        }
        this.json = mapper.writeValueAsString(servers);
    }

    @ModelAttribute("iceServersJson")
    String json() {
        return json;
    }
}
