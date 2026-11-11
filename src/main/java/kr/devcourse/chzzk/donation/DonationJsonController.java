package kr.devcourse.chzzk.donation;

import jakarta.servlet.http.HttpServletRequest;
import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 후원 주문 생성(JSON). 로그인한 회원만, CSRF 필요. 비로그인은 401. */
@RestController
class DonationJsonController {

    /** 요청 본문. 모자란 값은 서비스가 400으로 거절한다. */
    record CreateRequest(DonationKind kind, Long broadcastId, Long amount, String message) {
    }

    /** 결제창을 여는 데 필요한 값. */
    record OrderResponse(String orderId, long amount, String orderName, String clientKey, String successUrl,
                         String failUrl) {
    }

    private final DonationService donations;
    private final DonationAvailability availability;

    DonationJsonController(DonationService donations, DonationAvailability availability) {
        this.donations = donations;
        this.availability = availability;
    }

    @PostMapping("/donations")
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse create(@RequestBody CreateRequest request, @AuthenticationPrincipal MemberPrincipal me,
                         HttpServletRequest http) {
        if (!availability.isEnabled()) {
            throw new DonationUnavailableException();
        }
        if (me == null) {
            throw new DonationLoginRequiredException();
        }
        if (request.kind() == null || request.broadcastId() == null) {
            throw new InvalidDonationException("후원할 방송을 선택해 주세요.");
        }
        DonationService.OrderInfo order = donations.createOrder(request.kind(), request.broadcastId(),
                me.memberId(), request.amount(), request.message());
        String base = ServletUriComponentsBuilder.fromContextPath(http).build().toUriString();
        return new OrderResponse(order.orderId(), order.amount(), order.orderName(), availability.clientKey(),
                base + "/donations/success", base + "/donations/fail");
    }
}
