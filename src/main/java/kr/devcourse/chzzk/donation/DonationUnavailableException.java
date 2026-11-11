package kr.devcourse.chzzk.donation;

/** 결제 키가 설정되지 않아 후원을 받을 수 없다(503). */
class DonationUnavailableException extends RuntimeException {

    DonationUnavailableException() {
        super("결제 키가 설정되지 않아 후원을 받을 수 없습니다.");
    }
}
