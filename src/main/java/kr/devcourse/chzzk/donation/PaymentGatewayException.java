package kr.devcourse.chzzk.donation;

/** 결제사 호출이 실패했다. {@code code}는 결제사 오류 코드(예: ALREADY_PROCESSED_PAYMENT), 모르면 null. */
public class PaymentGatewayException extends RuntimeException {

    private final String code;

    public PaymentGatewayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
