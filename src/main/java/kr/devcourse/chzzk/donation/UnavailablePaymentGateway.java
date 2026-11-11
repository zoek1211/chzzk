package kr.devcourse.chzzk.donation;

/** 실제 결제사 구현이 없을 때 쓰는 자리표시 구현. 모든 호출이 실패한다. */
class UnavailablePaymentGateway implements PaymentGateway {

    private static PaymentGatewayException unavailable() {
        return new PaymentGatewayException("UNAVAILABLE", "결제 서비스를 사용할 수 없습니다.");
    }

    @Override
    public PaymentResult confirm(String paymentKey, String orderId, long amount) {
        throw unavailable();
    }

    @Override
    public PaymentResult query(String paymentKey) {
        throw unavailable();
    }

    @Override
    public void cancel(String paymentKey, String reason) {
        throw unavailable();
    }
}
