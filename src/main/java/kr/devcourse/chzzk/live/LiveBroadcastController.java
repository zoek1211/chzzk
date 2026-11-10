package kr.devcourse.chzzk.live;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

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
        model.addAttribute("live", live.find(id).orElseThrow(LiveNotFoundException::new));
        return "live/watch";
    }

    @PostMapping("/live")
    @ResponseBody
    public LiveBroadcastService.StartedLive start(@RequestBody StartRequest request) {
        return live.start(request.title());
    }

    @PostMapping("/live/{id}/end")
    @ResponseBody
    public void end(@PathVariable Long id,
                    @RequestHeader(value = "X-Broadcast-Key", required = false) String broadcastKey) {
        live.end(id, broadcastKey);
    }

    @ExceptionHandler(InvalidLiveStartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, String> rejectStart(InvalidLiveStartException e) {
        return Map.of("error", e.getMessage());
    }

    @ExceptionHandler(LiveNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    @ExceptionHandler(InvalidBroadcastKeyException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbidden() {
    }
}
