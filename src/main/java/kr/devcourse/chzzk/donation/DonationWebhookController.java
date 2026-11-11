package kr.devcourse.chzzk.donation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제사 웹훅(비로그인, CSRF 제외). 본문에서 {@code data.paymentKey}, {@code data.orderId}만 읽고, 확정은
 * 결제사 조회 API로 다시 확인한 뒤에만 한다. 모르는 주문·미완료·검증 실패는 아무것도 바꾸지 않으며 항상 200이다.
 */
@RestController
class DonationWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DonationWebhookController.class);

    record Webhook(Data data) {
        record Data(String paymentKey, String orderId) {
        }
    }

    private final DonationService donations;

    DonationWebhookController(DonationService donations) {
        this.donations = donations;
    }

    @PostMapping("/donations/webhook")
    @ResponseStatus(HttpStatus.OK)
    void webhook(@RequestBody(required = false) Webhook body) {
        if (body == null || body.data() == null || blank(body.data().paymentKey()) || blank(body.data().orderId())) {
            return;
        }
        try {
            donations.confirm(body.data().paymentKey(), body.data().orderId(), null);
        } catch (DonationOrderNotFoundException | DonationRejectedException e) {
            log.info("웹훅으로 후원을 확정하지 않음 order={}: {}", body.data().orderId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("웹훅 처리 실패 order={}", body.data().orderId(), e);
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.OK)
    void unreadable() {
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
