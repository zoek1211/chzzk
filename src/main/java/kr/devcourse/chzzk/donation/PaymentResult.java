package kr.devcourse.chzzk.donation;

/** 결제사가 돌려준 결제 정보. {@code status}는 결제사 상태 문자열(승인 완료는 {@code DONE}). */
public record PaymentResult(String status, long totalAmount, String orderId, String paymentKey) {

    public static final String DONE = "DONE";

    public boolean isDone() {
        return DONE.equals(status);
    }

    /** 더는 승인될 수 없는 끝난 상태(중단·만료·취소). 진행 중이거나 입금 대기는 아니다. */
    public boolean isTerminalFailure() {
        return "ABORTED".equals(status) || "EXPIRED".equals(status) || "CANCELED".equals(status)
                || "PARTIAL_CANCELED".equals(status);
    }
}
