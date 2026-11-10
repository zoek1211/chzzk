package kr.devcourse.chzzk.broadcast;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

/** 녹화방송 등록·상세·수정·삭제 화면. 등록·수정·삭제는 로그인 회원, 수정·삭제는 소유자만(규칙은 서비스가 검사). */
@Controller
public class RecordedBroadcastController {

    private final RecordedBroadcastService broadcasts;

    public RecordedBroadcastController(RecordedBroadcastService broadcasts) {
        this.broadcasts = broadcasts;
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
        RecordedBroadcastService.Upload upload = videoFile == null || videoFile.isEmpty() ? null
                : new RecordedBroadcastService.Upload(videoFile.getInputStream(), videoFile.getOriginalFilename());
        RecordedBroadcast saved = broadcasts.register(me.memberId(), title, videoUrl, upload);
        return "redirect:/broadcasts/" + saved.getId();
    }

    @GetMapping("/broadcasts/{id}/edit")
    public String editForm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        model.addAttribute("broadcast", broadcasts.findOwned(id, me.memberId()));
        return "broadcasts/edit";
    }

    @PostMapping("/broadcasts/{id}/edit")
    public String edit(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                       @RequestParam String title) {
        try {
            broadcasts.rename(id, me.memberId(), title);
        } catch (InvalidRegistrationException e) {
            throw new InvalidRenameException(id, e.getMessage());
        }
        return "redirect:/broadcasts/" + id;
    }

    @PostMapping("/broadcasts/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id) {
        broadcasts.delete(id, me.memberId());
        return "redirect:/broadcasts";
    }

    @GetMapping("/broadcasts/{id}")
    public String detail(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        RecordedBroadcast broadcast = broadcasts.find(id);
        model.addAttribute("broadcast", broadcast);
        model.addAttribute("ownedByMe", me != null && broadcast.isOwnedBy(me.memberId()));
        return "broadcasts/detail";
    }

    /** 등록 규칙 위반: 입력값을 채운 채 등록 화면을 다시 보여 준다. */
    @ExceptionHandler(InvalidRegistrationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String rejectRegistration(InvalidRegistrationException e, HttpServletRequest request, Model model) {
        model.addAttribute("error", e.getMessage());
        model.addAttribute("title", request.getParameter("title"));
        model.addAttribute("videoUrl", request.getParameter("videoUrl"));
        return "broadcasts/new";
    }

    /** 수정 규칙 위반: 수정 화면을 다시 보여 준다. */
    @ExceptionHandler(InvalidRenameException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String rejectRename(InvalidRenameException e, HttpServletRequest request, Model model) {
        model.addAttribute("error", e.getMessage());
        model.addAttribute("title", request.getParameter("title"));
        model.addAttribute("broadcast", broadcasts.find(e.broadcastId()));
        return "broadcasts/edit";
    }

    @ExceptionHandler(RecordedBroadcastNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    @ExceptionHandler(NotRecordedBroadcastOwnerException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbidden() {
    }
}
