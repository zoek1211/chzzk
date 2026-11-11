package kr.devcourse.chzzk.donation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.devcourse.chzzk.member.Member;

/**
 * 후원 주문: 주문 번호(유니크), 결제 키(유니크, 확정 때 채움), 방송(종류+id), 후원자, 수신자, 금액, 메시지, 상태.
 * 확정된 주문이 곧 후원이다. 방송이 삭제돼도 기록은 남는다(방송 id만 가진다).
 */
@Entity
@Table(name = "donation_order",
        indexes = @Index(columnList = "kind, broadcastId, status"))
class DonationOrder {

    static final int MAX_MESSAGE_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String orderId;

    @Column(unique = true, length = 200)
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DonationKind kind;

    @Column(nullable = false)
    private Long broadcastId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "donor_id", nullable = false)
    private Member donor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Member recipient;

    @Column(nullable = false)
    private long amount;

    @Column(length = MAX_MESSAGE_LENGTH)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DonationStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant confirmedAt;

    @Column(length = 300)
    private String failureReason;

    protected DonationOrder() {
    }

    DonationOrder(String orderId, DonationKind kind, Long broadcastId, Member donor, Member recipient,
                  long amount, String message) {
        this.orderId = orderId;
        this.kind = kind;
        this.broadcastId = broadcastId;
        this.donor = donor;
        this.recipient = recipient;
        this.amount = amount;
        this.message = message;
        this.status = DonationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    String getOrderId() {
        return orderId;
    }

    DonationKind getKind() {
        return kind;
    }

    Long getBroadcastId() {
        return broadcastId;
    }

    Member getDonor() {
        return donor;
    }

    Member getRecipient() {
        return recipient;
    }

    long getAmount() {
        return amount;
    }

    String getMessage() {
        return message;
    }

    DonationStatus getStatus() {
        return status;
    }

    Instant getConfirmedAt() {
        return confirmedAt;
    }
}
