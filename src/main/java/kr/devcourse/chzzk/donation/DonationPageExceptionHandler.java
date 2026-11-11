package kr.devcourse.chzzk.donation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

/** 결제창이 돌아오는 화면 경로의 오류 응답: 모두 결과 화면에 한국어 한 문장으로 보여 준다. */
@ControllerAdvice(assignableTypes = DonationController.class)
class DonationPageExceptionHandler {

    @ExceptionHandler(DonationOrderNotFoundException.class)
    ModelAndView notFound() {
        return page(HttpStatus.NOT_FOUND, "후원 주문을 찾을 수 없습니다.", null, null);
    }

    @ExceptionHandler(DonationForbiddenException.class)
    ModelAndView forbidden() {
        return page(HttpStatus.FORBIDDEN, "후원한 본인만 결제 결과를 확인할 수 있습니다.", null, null);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ModelAndView badRequest() {
        return page(HttpStatus.BAD_REQUEST, "요청 정보가 올바르지 않습니다.", null, null);
    }

    @ExceptionHandler(DonationRejectedException.class)
    ModelAndView rejected(DonationRejectedException e) {
        return page(HttpStatus.BAD_REQUEST, e.getMessage(), e.kind(), e.broadcastId());
    }

    @ExceptionHandler(DonationPendingException.class)
    ModelAndView pending(DonationPendingException e) {
        return page(HttpStatus.ACCEPTED, e.getMessage(), e.kind(), e.broadcastId());
    }

    private static ModelAndView page(HttpStatus status, String reason, DonationKind kind, Long broadcastId) {
        ModelAndView view = new ModelAndView("donations/result", status);
        view.addObject("success", false);
        view.addObject("kind", kind);
        view.addObject("broadcastId", broadcastId);
        view.addObject("reason", reason);
        return view;
    }
}
