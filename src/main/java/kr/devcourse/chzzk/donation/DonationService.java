package kr.devcourse.chzzk.donation;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastNotFoundException;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastService;
import kr.devcourse.chzzk.live.LiveBroadcastService;
import kr.devcourse.chzzk.live.LiveNotFoundException;
import kr.devcourse.chzzk.member.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후원 모듈의 공개 인터페이스: 주문 생성, 승인 통지 확정(성공 리다이렉트·웹훅), 방송별 합계·내역.
 * 화면은 Thymeleaf에서 {@code @donationService.list(...)}로 내역을 읽는다.
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    static final long MIN_AMOUNT = 1_000;
    static final long MAX_AMOUNT = 1_000_000;
    static final long AMOUNT_UNIT = 1_000;
    static final String ALREADY_FINISHED = "이미 처리가 끝난 후원 주문입니다.";
    static final String CANCELED_CODE = "PAY_PROCESS_CANCELED";

    /** 결제창을 여는 데 필요한 주문 정보. */
    public record OrderInfo(String orderId, long amount, String orderName) {
    }

    /** 확정된 후원 한 건(회원 id는 밖으로 내지 않는다). */
    public record DonationView(String donorNickname, long amount, String message, Instant confirmedAt) {
    }

    /** 확정이 끝난 후원. 결과 화면과 방송으로 돌아가는 링크에 쓴다. */
    public record Confirmed(DonationKind kind, Long broadcastId, long amount, String message) {
    }

    /**
     * 결제창이 실패·취소로 돌아온 뒤의 주문 결과. 이미 확정된 주문이면 {@code status}가 CONFIRMED다.
     * {@code failureReason}은 저장된 결제사 사유("[코드] 메시지")이고 없을 수 있다.
     */
    public record Ended(DonationStatus status, DonationKind kind, Long broadcastId, long amount, String message,
                        String failureReason) {
    }

    private final DonationOrderRepository orders;
    private final RecordedBroadcastService recorded;
    private final LiveBroadcastService live;
    private final PaymentGateway gateway;
    private final EntityManager entityManager;

    DonationService(DonationOrderRepository orders, RecordedBroadcastService recorded,
                    LiveBroadcastService live, PaymentGateway gateway, EntityManager entityManager) {
        this.orders = orders;
        this.recorded = recorded;
        this.live = live;
        this.gateway = gateway;
        this.entityManager = entityManager;
    }

    /**
     * 회원이 방송 소유자에게 후원할 주문을 만든다(수신자는 서버가 방송 소유자로 정한다).
     *
     * @throws DonationBroadcastNotFoundException 없는 방송
     * @throws InvalidDonationException 소유자 없는 방송, 자기 방송, 금액·메시지 규칙 위반
     */
    @Transactional
    public OrderInfo createOrder(DonationKind kind, Long broadcastId, Long donorId, Long amount, String message) {
        String title = titleOf(kind, broadcastId);
        Long ownerId = ownerIdOf(kind, broadcastId)
                .orElseThrow(() -> new InvalidDonationException("소유자가 없는 방송에는 후원할 수 없습니다."));
        if (ownerId.equals(donorId)) {
            throw new InvalidDonationException("자기 방송에는 후원할 수 없습니다.");
        }
        if (amount == null || amount < MIN_AMOUNT || amount > MAX_AMOUNT || amount % AMOUNT_UNIT != 0) {
            throw new InvalidDonationException("후원 금액은 1,000원 단위로 1,000원 이상 1,000,000원 이하여야 합니다.");
        }
        String validMessage = message == null || message.isBlank() ? null : message;
        if (validMessage != null && validMessage.length() > DonationOrder.MAX_MESSAGE_LENGTH) {
            throw new InvalidDonationException(
                    "후원 메시지는 " + DonationOrder.MAX_MESSAGE_LENGTH + "자 이하로 입력해 주세요.");
        }
        Member donor = entityManager.getReference(Member.class, donorId);
        Member recipient = entityManager.getReference(Member.class, ownerId);
        DonationOrder order = orders.save(new DonationOrder(UUID.randomUUID().toString(), kind, broadcastId,
                donor, recipient, amount, validMessage));
        return new OrderInfo(order.getOrderId(), amount, orderName(title));
    }

    /**
     * 성공 리다이렉트를 확정한다. 후원한 본인만 할 수 있다. 이미 확정된 주문이면 같은 결과를 돌려주고 아무것도
     * 바꾸지 않는다. 금액이 주문과 같은지 보고 결제사 승인 API를 부르며, 승인 호출이 실패하면 조회 API로 다시
     * 확인한다(일시 오류로 주문을 실패로 닫지 않는다). 결제사 응답(상태 DONE·금액·주문 번호)과 방송·소유자(=수신자)를
     * 확인한 뒤에만 확정하고, 이 주문의 결제가 검증에 실패하면 결제사에 취소를 요청한다(남의 결제는 취소하지 않는다).
     *
     * @throws DonationOrderNotFoundException 없는 주문
     * @throws DonationForbiddenException 후원한 본인이 아님
     * @throws DonationRejectedException 확정하지 못함(주문은 실패로 닫힘)
     * @throws DonationPendingException 결제사 확인이 끝나지 않아 대기로 남김
     */
    public Confirmed confirmRedirect(Long memberId, String paymentKey, String orderId, long amount) {
        DonationOrder order = ownOrder(memberId, orderId);
        return confirm(order, paymentKey, ApprovalNotice.REDIRECT, amount);
    }

    /**
     * 웹훅 통지를 확정한다. 본문을 믿지 않고 결제사 조회 API로 확인하며, 어긋나거나 결제가 끝나지 않았으면 주문을
     * 바꾸지 않고 결제도 취소하지 않는다(이후 통지로 확정될 수 있다).
     *
     * @throws DonationOrderNotFoundException 없는 주문
     * @throws DonationRejectedException 확정하지 못함(아무것도 바뀌지 않음)
     */
    public Confirmed confirmWebhook(String paymentKey, String orderId) {
        DonationOrder order = orders.findWithDonor(orderId).orElseThrow(DonationOrderNotFoundException::new);
        return confirm(order, paymentKey, ApprovalNotice.WEBHOOK, null);
    }

    private Confirmed confirm(DonationOrder order, String paymentKey, ApprovalNotice notice, Long redirectAmount) {
        String orderId = order.getOrderId();
        if (order.getStatus() == DonationStatus.CONFIRMED) {
            return confirmed(order);
        }
        if (order.getStatus() != DonationStatus.PENDING) {
            throw rejected(order, ALREADY_FINISHED);
        }
        if (notice == ApprovalNotice.REDIRECT && redirectAmount != order.getAmount()) {
            throw reject(order, "결제 금액이 후원 주문의 금액과 달라 후원을 확정하지 않았습니다.", notice);
        }

        PaymentResult result = notice == ApprovalNotice.REDIRECT
                ? approve(order, paymentKey) : queryForWebhook(order, paymentKey);
        Mismatch mismatch = mismatch(order, result, notice);
        if (mismatch != null) {
            if (mismatch.ownPayment() && notice == ApprovalNotice.REDIRECT) {
                cancelQuietly(paymentKey, "후원 확정 검증 실패");
            }
            if (mismatch.pending()) {
                throw new DonationPendingException(order.getKind(), order.getBroadcastId());
            }
            throw reject(order, mismatch.reason(), notice);
        }

        int updated;
        try {
            updated = orders.confirmIfPending(orderId, paymentKey, Instant.now());
        } catch (DataIntegrityViolationException e) {
            // 결제 키가 이미 다른 후원에 쓰였다. 그 결제는 이 주문의 것이 아니므로 취소하지 않는다.
            throw reject(order, "이미 다른 후원에 쓰인 결제입니다.", notice);
        }
        if (updated == 0) {
            DonationOrder current = orders.findWithDonor(orderId).orElseThrow(DonationOrderNotFoundException::new);
            if (current.getStatus() == DonationStatus.CONFIRMED) {
                return confirmed(current);
            }
            throw rejected(current, ALREADY_FINISHED);
        }
        return confirmed(order);
    }

    /**
     * 결제창 실패·취소 리다이렉트를 주문에 반영한다. 후원한 본인만 할 수 있다. 대기 상태일 때만 바꾸고(취소면
     * CANCELED, 그 밖은 FAILED), 이미 처리가 끝난 주문은 그대로 둔다(확정된 주문은 확정 유지).
     *
     * @throws DonationOrderNotFoundException 없는 주문
     * @throws DonationForbiddenException 후원한 본인이 아님
     */
    public Ended recordFailure(Long memberId, String orderId, String code, String message) {
        DonationOrder order = ownOrder(memberId, orderId);
        if (order.getStatus() == DonationStatus.PENDING) {
            String reason = "[" + (code == null ? "" : code) + "] " + (message == null ? "" : message);
            reason = reason.length() > 300 ? reason.substring(0, 300) : reason;
            if (CANCELED_CODE.equals(code)) {
                orders.cancelIfPending(orderId, reason);
            } else {
                orders.failIfPending(orderId, reason);
            }
            order = orders.findWithDonor(orderId).orElseThrow(DonationOrderNotFoundException::new);
        }
        return new Ended(order.getStatus(), order.getKind(), order.getBroadcastId(), order.getAmount(),
                order.getMessage(), order.getFailureReason());
    }

    /** 방송의 확정된 후원을 최신 순으로 돌려준다. */
    @Transactional(readOnly = true)
    public List<DonationView> list(DonationKind kind, Long broadcastId) {
        return orders.findConfirmedNewestFirst(kind, broadcastId).stream()
                .map(o -> new DonationView(o.getDonor().getNickname(), o.getAmount(), o.getMessage(),
                        o.getConfirmedAt()))
                .toList();
    }

    /** 방송의 확정된 후원 합계(원). */
    @Transactional(readOnly = true)
    public long total(DonationKind kind, Long broadcastId) {
        return orders.sumConfirmed(kind, broadcastId);
    }

    /** 이 회원이 이 방송에 후원할 수 있는지(로그인, 방송에 소유자가 있음, 본인 방송 아님). */
    @Transactional(readOnly = true)
    public boolean canDonate(DonationKind kind, Long broadcastId, Long memberId) {
        if (memberId == null) {
            return false;
        }
        try {
            return ownerIdOf(kind, broadcastId).map(ownerId -> !ownerId.equals(memberId)).orElse(false);
        } catch (DonationBroadcastNotFoundException e) {
            return false;
        }
    }

    private DonationOrder ownOrder(Long memberId, String orderId) {
        DonationOrder order = orders.findWithDonor(orderId).orElseThrow(DonationOrderNotFoundException::new);
        if (memberId == null || !order.getDonor().getId().equals(memberId)) {
            throw new DonationForbiddenException();
        }
        return order;
    }

    /** 승인 호출. 실패하면 조회로 다시 확인하고, 그래도 모르면 주문을 대기로 남긴다. */
    private PaymentResult approve(DonationOrder order, String paymentKey) {
        try {
            return gateway.confirm(paymentKey, order.getOrderId(), order.getAmount());
        } catch (PaymentGatewayException e) {
            log.warn("결제 승인 호출 실패 order={} key={} code={}", order.getOrderId(), mask(paymentKey), e.code());
            if (isCardRejection(e.code())) {
                throw reject(order, "결제가 승인되지 않아 후원을 확정하지 않았습니다.", ApprovalNotice.REDIRECT);
            }
        }
        try {
            return gateway.query(paymentKey);
        } catch (PaymentGatewayException e) {
            log.warn("결제 조회 실패 order={} key={} code={}", order.getOrderId(), mask(paymentKey), e.code());
            throw new DonationPendingException(order.getKind(), order.getBroadcastId());
        }
    }

    private PaymentResult queryForWebhook(DonationOrder order, String paymentKey) {
        try {
            return gateway.query(paymentKey);
        } catch (PaymentGatewayException e) {
            log.warn("결제 조회 실패 order={} key={} code={}", order.getOrderId(), mask(paymentKey), e.code());
            throw rejected(order, "결제 승인을 확인하지 못했습니다.");
        }
    }

    /** 확정할 수 없는 사유. {@code ownPayment}는 결제가 이 주문의 것인지, {@code pending}은 실패로 닫지 않고 대기로 둘지. */
    private record Mismatch(String reason, boolean ownPayment, boolean pending) {
    }

    /** 결제사 응답과 방송 상태가 주문과 맞는지 확인한다. 맞으면 null. */
    private Mismatch mismatch(DonationOrder order, PaymentResult result, ApprovalNotice notice) {
        if (!order.getOrderId().equals(result.orderId())) {
            return new Mismatch("결제 정보가 후원 주문과 달라 후원을 확정하지 않았습니다.", false, false);
        }
        if (!result.isDone()) {
            boolean stillOpen = !result.isTerminalFailure() && notice == ApprovalNotice.REDIRECT;
            return new Mismatch("결제가 완료되지 않아 후원을 확정하지 않았습니다.", false, stillOpen);
        }
        if (result.totalAmount() != order.getAmount()) {
            return new Mismatch("결제 금액이 후원 주문의 금액과 달라 후원을 확정하지 않았습니다.", true, false);
        }
        Optional<Long> ownerId;
        try {
            ownerId = ownerIdOf(order.getKind(), order.getBroadcastId());
        } catch (DonationBroadcastNotFoundException e) {
            return new Mismatch("후원할 방송이 더는 없어 후원을 확정하지 않았습니다.", true, false);
        }
        if (ownerId.isEmpty() || !ownerId.get().equals(order.getRecipient().getId())) {
            return new Mismatch("방송 소유자가 바뀌어 후원을 확정하지 않았습니다.", true, false);
        }
        return null;
    }

    /** 토스가 돌려주는 확정적인 카드 거절 코드(다시 조회해도 달라지지 않는다). */
    private static boolean isCardRejection(String code) {
        return code != null && (code.startsWith("REJECT_") || code.startsWith("INVALID_CARD")
                || code.equals("INVALID_STOPPED_CARD") || code.equals("EXCEED_MAX_CARD_INSTALLMENT_PLAN"));
    }

    /** 리다이렉트는 주문을 실패로 닫는다. 웹훅은 닫지 않는다(결제가 아직 끝나지 않았을 수 있어, 이후 통지로 확정될 수 있다). */
    private DonationRejectedException reject(DonationOrder order, String reason, ApprovalNotice notice) {
        if (notice == ApprovalNotice.REDIRECT) {
            orders.failIfPending(order.getOrderId(), reason);
        }
        return rejected(order, reason);
    }

    private static DonationRejectedException rejected(DonationOrder order, String reason) {
        return new DonationRejectedException(reason, order.getKind(), order.getBroadcastId());
    }

    private void cancelQuietly(String paymentKey, String reason) {
        try {
            gateway.cancel(paymentKey, reason);
        } catch (RuntimeException e) {
            log.error("결제 취소 실패 key={}", mask(paymentKey), e);
        }
    }

    /** 결제 키는 로그에 앞 4자만 남긴다. */
    static String mask(String paymentKey) {
        return paymentKey == null || paymentKey.length() <= 4 ? "****" : paymentKey.substring(0, 4) + "****";
    }

    private static Confirmed confirmed(DonationOrder order) {
        return new Confirmed(order.getKind(), order.getBroadcastId(), order.getAmount(), order.getMessage());
    }

    private static String orderName(String title) {
        String name = title + " 후원";
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private String titleOf(DonationKind kind, Long id) {
        try {
            return switch (kind) {
                case RECORDED -> recorded.find(id).getTitle();
                case LIVE -> live.find(id).orElseThrow(LiveNotFoundException::new).getTitle();
            };
        } catch (RecordedBroadcastNotFoundException | LiveNotFoundException e) {
            throw new DonationBroadcastNotFoundException();
        }
    }

    /** 방송 모듈이 답한 소유자 id. 소유자 없는 방송이면 비어 있다. */
    private Optional<Long> ownerIdOf(DonationKind kind, Long id) {
        try {
            return switch (kind) {
                case RECORDED -> recorded.ownerIdOf(id);
                case LIVE -> live.ownerIdOf(id);
            };
        } catch (RecordedBroadcastNotFoundException | LiveNotFoundException e) {
            throw new DonationBroadcastNotFoundException();
        }
    }
}
