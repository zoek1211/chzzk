package kr.devcourse.chzzk.donation;

/** 후원 요청이 규칙을 어겼다(400). 메시지는 사용자에게 그대로 보여 줄 수 있다. */
class InvalidDonationException extends RuntimeException {

    InvalidDonationException(String message) {
        super(message);
    }
}
