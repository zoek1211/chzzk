package kr.devcourse.chzzk.donation;

/**
 * 결제사 확인이 일시 오류나 진행 중 상태로 끝나지 않아 주문을 대기로 남겼다. 이후 웹훅이나 다시 열기로 확정될 수 있다.
 * 방송으로 돌아가는 링크에 {@code kind}·{@code broadcastId}를 쓴다.
 */
class DonationPendingException extends RuntimeException {

    private final DonationKind kind;
    private final Long broadcastId;

    DonationPendingException(DonationKind kind, Long broadcastId) {
        super("결제 확인이 지연되고 있으며 결제가 확인되면 후원이 반영됩니다.");
        this.kind = kind;
        this.broadcastId = broadcastId;
    }

    DonationKind kind() {
        return kind;
    }

    Long broadcastId() {
        return broadcastId;
    }
}
