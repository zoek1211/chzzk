package kr.devcourse.chzzk.donation;

/**
 * 승인 통지를 확정하지 못했다(금액 불일치, 결제사 상태 이상, 수신자 변경, 이미 처리된 주문 등).
 * 메시지는 사용자에게 그대로 보여 줄 수 있고, 방송으로 돌아가는 링크에 {@code kind}·{@code broadcastId}를 쓴다.
 */
public class DonationRejectedException extends RuntimeException {

    private final DonationKind kind;
    private final Long broadcastId;

    DonationRejectedException(String message, DonationKind kind, Long broadcastId) {
        super(message);
        this.kind = kind;
        this.broadcastId = broadcastId;
    }

    public DonationKind kind() {
        return kind;
    }

    public Long broadcastId() {
        return broadcastId;
    }
}
