package kr.devcourse.chzzk.list;

import kr.devcourse.chzzk.broadcast.RecordedBroadcastRepository;
import kr.devcourse.chzzk.live.LiveBroadcastService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 방송 목록: 라이브 방송과 녹화방송을 한 화면에 함께 보여 준다. 첫 화면도 이 목록이다. */
@Controller
public class BroadcastListController {

    private final LiveBroadcastService liveBroadcasts;
    private final RecordedBroadcastRepository recordedBroadcasts;

    public BroadcastListController(LiveBroadcastService liveBroadcasts,
                                   RecordedBroadcastRepository recordedBroadcasts) {
        this.liveBroadcasts = liveBroadcasts;
        this.recordedBroadcasts = recordedBroadcasts;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/broadcasts";
    }

    @GetMapping("/broadcasts")
    public String list(Model model) {
        model.addAttribute("liveBroadcasts", liveBroadcasts.list());
        model.addAttribute("broadcasts", recordedBroadcasts.findAllByOrderByRegisteredAtDescIdDesc());
        return "broadcasts/list";
    }
}
