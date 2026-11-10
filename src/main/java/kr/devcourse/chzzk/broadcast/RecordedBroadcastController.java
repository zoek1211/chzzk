package kr.devcourse.chzzk.broadcast;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import kr.devcourse.chzzk.member.Member;
import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import kr.devcourse.chzzk.video.VideoStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** 녹화방송 등록·상세·수정·삭제. 등록·수정·삭제는 로그인 회원, 수정·삭제는 소유자만. */
@Controller
public class RecordedBroadcastController {

    private final RecordedBroadcastRepository broadcasts;
    private final VideoStorage videoStorage;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    public RecordedBroadcastController(RecordedBroadcastRepository broadcasts, VideoStorage videoStorage,
                                       EntityManager entityManager,
                                       ApplicationEventPublisher events) {
        this.broadcasts = broadcasts;
        this.videoStorage = videoStorage;
        this.entityManager = entityManager;
        this.events = events;
    }

    @GetMapping("/broadcasts/new")
    public String registerForm() {
        return "broadcasts/new";
    }

    @PostMapping("/broadcasts")
    public String register(@AuthenticationPrincipal MemberPrincipal me,
                           @RequestParam String title,
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
        broadcast.ownedBy(entityManager.getReference(Member.class, me.memberId()));
        RecordedBroadcast saved = broadcasts.save(broadcast);
        return "redirect:/broadcasts/" + saved.getId();
    }

    @ExceptionHandler(InvalidRegistrationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String rejectRegistration(InvalidRegistrationException e, HttpServletRequest request, Model model) {
        model.addAttribute("error", e.getMessage());
        model.addAttribute("title", request.getParameter("title"));
        model.addAttribute("videoUrl", request.getParameter("videoUrl"));
        if (request.getRequestURI().endsWith("/edit")) {
            model.addAttribute("broadcast", broadcasts.findById(pathId(request)).orElse(null));
            return "broadcasts/edit";
        }
        return "broadcasts/new";
    }

    private static Long pathId(HttpServletRequest request) {
        String[] parts = request.getRequestURI().split("/");
        return Long.valueOf(parts[parts.length - 2]);
    }

    @GetMapping("/broadcasts/{id}/edit")
    public String editForm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        model.addAttribute("broadcast", ownedBroadcast(id, me));
        return "broadcasts/edit";
    }

    @PostMapping("/broadcasts/{id}/edit")
    public String edit(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                       @RequestParam String title) {
        RecordedBroadcast broadcast = ownedBroadcast(id, me);
        broadcast.rename(title);
        broadcasts.save(broadcast);
        return "redirect:/broadcasts/" + id;
    }

    @PostMapping("/broadcasts/{id}/delete")
    @Transactional
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id) {
        RecordedBroadcast broadcast = ownedBroadcast(id, me);
        broadcasts.delete(broadcast);
        events.publishEvent(new RecordedBroadcastDeleted(id));
        if (broadcast.getVideoSource() == VideoSource.UPLOADED) {
            videoStorage.delete(broadcast.getVideoLocation());
        }
        return "redirect:/broadcasts";
    }

    /** 없으면 404, 소유자가 아니면(소유자 없는 방송 포함) 403. */
    private RecordedBroadcast ownedBroadcast(Long id, MemberPrincipal me) {
        RecordedBroadcast broadcast = broadcasts.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (me == null || !broadcast.isOwnedBy(me.memberId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return broadcast;
    }

    @GetMapping("/broadcasts/{id}")
    public String detail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        RecordedBroadcast broadcast = broadcasts.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("broadcast", broadcast);
        model.addAttribute("ownedByMe", me != null && broadcast.isOwnedBy(me.memberId()));
        return "broadcasts/detail";
    }
}
