package kr.devcourse.chzzk.donation;

import kr.devcourse.chzzk.member.MemberPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 결제창이 돌아오는 화면 경로. 토스 리다이렉트는 후원자 본인의 브라우저에서 일어나므로 로그인(비로그인은 로그인
 * 화면으로 302)과 주문의 후원자 본인 여부를 요구하고, 확정은 서버가 결제사에 확인한 뒤에만 한다.
 */
@Controller
class DonationController {

    private final DonationService donations;

    DonationController(DonationService donations) {
        this.donations = donations;
    }

    @GetMapping("/donations/success")
    String success(@RequestParam String paymentKey, @RequestParam String orderId, @RequestParam long amount,
                   @AuthenticationPrincipal MemberPrincipal me, Model model) {
        DonationService.Confirmed confirmed = donations.confirmRedirect(me.memberId(), paymentKey, orderId, amount);
        model.addAttribute("success", true);
        model.addAttribute("kind", confirmed.kind());
        model.addAttribute("broadcastId", confirmed.broadcastId());
        model.addAttribute("amount", confirmed.amount());
        model.addAttribute("message", confirmed.message());
        return "donations/result";
    }

    @GetMapping("/donations/fail")
    String fail(@RequestParam(required = false) String code, @RequestParam(required = false) String message,
                @RequestParam String orderId, @AuthenticationPrincipal MemberPrincipal me, Model model) {
        DonationService.Ended ended = donations.recordFailure(me.memberId(), orderId, code, message);
        model.addAttribute("kind", ended.kind());
        model.addAttribute("broadcastId", ended.broadcastId());
        if (ended.status() == DonationStatus.CONFIRMED) {
            model.addAttribute("success", true);
            model.addAttribute("amount", ended.amount());
            model.addAttribute("message", ended.message());
        } else if (ended.status() == DonationStatus.CANCELED) {
            model.addAttribute("success", false);
            model.addAttribute("reason", "결제를 취소해 후원하지 않았습니다.");
        } else {
            model.addAttribute("success", false);
            model.addAttribute("reason", "결제에 실패해 후원하지 않았습니다.");
            model.addAttribute("detail", ended.failureReason());
        }
        return "donations/result";
    }
}
