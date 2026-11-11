package kr.devcourse.chzzk.donation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface DonationOrderRepository extends JpaRepository<DonationOrder, Long> {

    @Query("select o from DonationOrder o join fetch o.donor join fetch o.recipient where o.orderId = :orderId")
    Optional<DonationOrder> findWithDonor(String orderId);

    @Query("""
            select o from DonationOrder o join fetch o.donor
            where o.kind = :kind and o.broadcastId = :broadcastId and o.status = kr.devcourse.chzzk.donation.DonationStatus.CONFIRMED
            order by o.confirmedAt desc, o.id desc
            """)
    List<DonationOrder> findConfirmedNewestFirst(DonationKind kind, Long broadcastId);

    @Query("""
            select coalesce(sum(o.amount), 0) from DonationOrder o
            where o.kind = :kind and o.broadcastId = :broadcastId and o.status = kr.devcourse.chzzk.donation.DonationStatus.CONFIRMED
            """)
    long sumConfirmed(DonationKind kind, Long broadcastId);

    /** 대기 → 확정 조건부 갱신. 바뀐 행 수(0이면 이미 다른 상태)를 돌려준다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DonationOrder o set o.status = kr.devcourse.chzzk.donation.DonationStatus.CONFIRMED,
                o.paymentKey = :paymentKey, o.confirmedAt = :now
            where o.orderId = :orderId and o.status = kr.devcourse.chzzk.donation.DonationStatus.PENDING
            """)
    int confirmIfPending(String orderId, String paymentKey, Instant now);

    /** 대기 → 실패 조건부 갱신. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DonationOrder o set o.status = kr.devcourse.chzzk.donation.DonationStatus.FAILED,
                o.failureReason = :reason
            where o.orderId = :orderId and o.status = kr.devcourse.chzzk.donation.DonationStatus.PENDING
            """)
    int failIfPending(String orderId, String reason);

    /** 대기 → 취소 조건부 갱신(결제창에서 구매자가 닫은 경우). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update DonationOrder o set o.status = kr.devcourse.chzzk.donation.DonationStatus.CANCELED,
                o.failureReason = :reason
            where o.orderId = :orderId and o.status = kr.devcourse.chzzk.donation.DonationStatus.PENDING
            """)
    int cancelIfPending(String orderId, String reason);
}
