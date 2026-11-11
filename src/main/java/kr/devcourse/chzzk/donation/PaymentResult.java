package kr.devcourse.chzzk.donation;

/** 결제사가 돌려준 결제 정보. {@code status}는 결제사 상태 문자열(승인 완료는 {@code DONE}). */
public record PaymentResult(String status, long totalAmount, String orderId, String paymentKey) {

    public static final String DONE = "DONE";

    public boolean isDone() {
        return DONE.equals(status);
    }
}
