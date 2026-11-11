package kr.devcourse.chzzk.donation;

/**
 * 결제사 경계. 실제 구현은 토스페이먼츠 REST API, 테스트는 가짜 구현.
 * 결제사가 요청을 거절하거나 닿지 않으면 {@link PaymentGatewayException}을 던진다.
 */
public interface PaymentGateway {

    /** 결제 승인. 리다이렉트로 받은 결제 키·주문 번호·금액을 결제사에 보내 승인받는다. */
    PaymentResult confirm(String paymentKey, String orderId, long amount);

    /** 결제 조회. 웹훅처럼 본문을 믿지 않고 결제사의 실제 상태를 다시 읽는다. */
    PaymentResult query(String paymentKey);

    /** 결제 취소(전액). 승인된 결제를 검증 실패로 되돌릴 때 쓴다. */
    void cancel(String paymentKey, String reason);
}
