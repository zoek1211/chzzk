package kr.devcourse.chzzk.donation;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 후원 JSON 요청(주문 생성)의 오류 응답: {@code {"message"}} 본문. */
@ControllerAdvice(assignableTypes = DonationJsonController.class)
class DonationExceptionHandler {

    @ExceptionHandler(DonationBroadcastNotFoundException.class)
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
}
