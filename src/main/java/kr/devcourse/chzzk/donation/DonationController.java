package kr.devcourse.chzzk.donation;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 결제창이 돌아오는 화면 경로(공개 GET). 로그인이 아니라 주문으로 판단하고, 확정은 서버가 결제사에 확인한 뒤에만 한다. */
@Controller
class DonationController {

    private final DonationService donations;

    DonationController(DonationService donations) {
        this.donations = donations;
    }

    @GetMapping("/donations/success")
    String success(@RequestParam String paymentKey, @RequestParam String orderId, @RequestParam long amount,
                   Model model) {
        DonationService.Confirmed confirmed = donations.confirm(paymentKey, orderId, amount);
        model.addAttribute("success", true);
        model.addAttribute("kind", confirmed.kind());
        model.addAttribute("broadcastId", confirmed.broadcastId());
        model.addAttribute("amount", confirmed.amount());
        model.addAttribute("message", confirmed.message());
        return "donations/result";
    }
}
