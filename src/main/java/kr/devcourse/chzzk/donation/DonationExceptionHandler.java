package kr.devcourse.chzzk.donation;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

/** 후원 요청의 오류 응답: JSON 경로는 {@code {"message"}}, 화면 경로는 실패 결과 화면. */
@ControllerAdvice(assignableTypes = {DonationJsonController.class, DonationController.class})
class DonationExceptionHandler {

    @ExceptionHandler({DonationBroadcastNotFoundException.class, DonationOrderNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void notFound() {
    }

    @ExceptionHandler(DonationLoginRequiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void unauthorized() {
    }

    @ExceptionHandler(DonationUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(DonationUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(InvalidDonationException.class)
    ResponseEntity<Map<String, String>> invalid(InvalidDonationException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "후원 요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(DonationRejectedException.class)
    ModelAndView rejected(DonationRejectedException e) {
        ModelAndView view = new ModelAndView("donations/result", HttpStatus.BAD_REQUEST);
        view.addObject("success", false);
        view.addObject("kind", e.kind());
        view.addObject("broadcastId", e.broadcastId());
        view.addObject("reason", e.getMessage());
        return view;
    }
}
