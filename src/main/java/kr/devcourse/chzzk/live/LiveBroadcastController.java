package kr.devcourse.chzzk.live;

import java.util.Map;
import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

/** 라이브 방송 시작·송출 화면·시청 화면. 시작·종료·삭제는 로그인한 회원(소유자)만 할 수 있다. 로그인 여부는 서버가 검사한다. */
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
    public LiveBroadcastService.StartedLive start(@RequestBody StartRequest request,
                                                  @AuthenticationPrincipal MemberPrincipal me) {
        return live.start(request.title(), requireLogin(me).memberId());
    }

    @PostMapping("/live/{id}/end")
    @ResponseBody
    public void end(@PathVariable Long id, @AuthenticationPrincipal MemberPrincipal me) {
        live.end(id, requireLogin(me).memberId());
    }

    @PostMapping("/live/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal MemberPrincipal me) {
        live.delete(id, requireLogin(me).memberId());
        return "redirect:/broadcasts";
    }

    private static MemberPrincipal requireLogin(MemberPrincipal me) {
        if (me == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return me;
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

    @ExceptionHandler(NotLiveOwnerException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbidden() {
    }

    @ExceptionHandler(LiveConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    @ResponseBody
    public Map<String, String> conflict(LiveConflictException e) {
        return Map.of("error", e.getMessage());
    }
}
