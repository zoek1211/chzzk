package kr.devcourse.chzzk.broadcast;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import kr.devcourse.chzzk.video.VideoStorage;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** 녹화방송 등록·목록·상세 화면. 이번 단계에서는 로그인 없이 누구나 사용한다. */
@Controller
public class RecordedBroadcastController {

    private final RecordedBroadcastRepository broadcasts;
    private final VideoStorage videoStorage;

    public RecordedBroadcastController(RecordedBroadcastRepository broadcasts, VideoStorage videoStorage) {
        this.broadcasts = broadcasts;
        this.videoStorage = videoStorage;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/broadcasts";
    }

    @GetMapping("/broadcasts")
    public String list(Model model) {
        model.addAttribute("broadcasts", broadcasts.findAllByOrderByRegisteredAtDescIdDesc());
        return "broadcasts/list";
    }

    @GetMapping("/broadcasts/new")
    public String registerForm() {
        return "broadcasts/new";
    }

    @PostMapping("/broadcasts")
    public String register(@RequestParam String title,
                           @RequestParam(required = false) String videoUrl,
                           @RequestParam(required = false) MultipartFile videoFile) throws IOException {
        boolean hasFile = videoFile != null && !videoFile.isEmpty();
        boolean hasUrl = videoUrl != null && !videoUrl.isBlank();
        if (hasFile == hasUrl) {
            throw new InvalidRegistrationException(hasFile
                    ? "영상 파일과 영상 주소 중 하나만 입력해 주세요."
                    : "영상 파일을 올리거나 영상 주소를 입력해 주세요.");
        }

        RecordedBroadcast broadcast;
        if (hasFile) {
            // 파일을 보관하기 전에 제목부터 검사해 버려질 파일이 남지 않게 한다.
            String validTitle = RecordedBroadcast.validTitle(title);
            String storedName = videoStorage.store(videoFile.getInputStream(), videoFile.getOriginalFilename());
            broadcast = RecordedBroadcast.withUploadedVideo(validTitle, storedName);
        } else {
            broadcast = RecordedBroadcast.withExternalVideo(title, videoUrl);
        }
        RecordedBroadcast saved = broadcasts.save(broadcast);
        return "redirect:/broadcasts/" + saved.getId();
    }

    @ExceptionHandler(InvalidRegistrationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String rejectRegistration(InvalidRegistrationException e, HttpServletRequest request, Model model) {
        model.addAttribute("error", e.getMessage());
        model.addAttribute("title", request.getParameter("title"));
        model.addAttribute("videoUrl", request.getParameter("videoUrl"));
        return "broadcasts/new";
    }

    @GetMapping("/broadcasts/{id}")
    public String detail(@PathVariable Long id, Model model) {
        RecordedBroadcast broadcast = broadcasts.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("broadcast", broadcast);
        return "broadcasts/detail";
    }
}
