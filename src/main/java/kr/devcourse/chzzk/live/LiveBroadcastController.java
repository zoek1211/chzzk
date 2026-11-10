package kr.devcourse.chzzk.live;

import java.util.Map;
import kr.devcourse.chzzk.broadcast.InvalidRegistrationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/** 라이브 방송 시작·송출 화면·시청 화면. 로그인 없이 누구나 시작할 수 있다. */
@Controller
public class LiveBroadcastController {

    private final LiveBroadcastService live;

    public LiveBroadcastController(LiveBroadcastService live) {
        this.live = live;
    }

    public record StartRequest(String title) {
    }

    @GetMapping("/live/new")
    public String broadcastScreen() {
        return "live/new";
    }

    @GetMapping("/live/{id}")
    public String watch(@PathVariable Long id, Model model) {
        LiveBroadcast broadcast = live.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("live", broadcast);
        return "live/watch";
    }

    @PostMapping("/live")
    @ResponseBody
    public ResponseEntity<?> start(@RequestBody StartRequest request) {
        try {
            return ResponseEntity.ok(live.start(request.title()));
        } catch (InvalidRegistrationException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }
}
