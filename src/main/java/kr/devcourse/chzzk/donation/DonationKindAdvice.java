package kr.devcourse.chzzk.donation;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 화면 템플릿이 후원 조각에 방송 종류를 문자열 없이 넘길 수 있게 두 종류를 모델에 싣는다. */
@ControllerAdvice
class DonationKindAdvice {

    @ModelAttribute("donationRecordedKind")
    DonationKind recordedKind() {
        return DonationKind.RECORDED;
    }

    @ModelAttribute("donationLiveKind")
    DonationKind liveKind() {
        return DonationKind.LIVE;
    }
}
