package kr.devcourse.chzzk.donation;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastRepository;
import kr.devcourse.chzzk.live.LiveBroadcastRepository;
import kr.devcourse.chzzk.member.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후원 모듈의 공개 인터페이스: 주문 생성, 승인 통지 확정(성공 리다이렉트·웹훅 공통), 방송별 합계·내역.
 * 화면은 Thymeleaf에서 {@code @donationService.list(...)}로 내역을 읽는다.
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    static final long MIN_AMOUNT = 1_000;
    static final long MAX_AMOUNT = 1_000_000;
    static final long AMOUNT_UNIT = 1_000;
    static final String ALREADY_PROCESSED = "ALREADY_PROCESSED_PAYMENT";

    /** 결제창을 여는 데 필요한 주문 정보. */
    public record OrderInfo(String orderId, long amount, String orderName) {
    }

    /** 확정된 후원 한 건(회원 id는 밖으로 내지 않는다). */
    public record DonationView(String donorNickname, long amount, String message, Instant confirmedAt) {
    }

    /** 확정이 끝난 후원. 결과 화면과 방송으로 돌아가는 링크에 쓴다. */
    public record Confirmed(DonationKind kind, Long broadcastId, long amount, String message) {
    }

    /** 결제창이 실패·취소로 돌아온 뒤의 주문 결과. 이미 확정된 주문이면 {@code status}가 CONFIRMED다. */
    public record Ended(DonationStatus status, DonationKind kind, Long broadcastId, long amount, String message) {
    }

    static final String CANCELED_CODE = "PAY_PROCESS_CANCELED";

    private record Broadcast(String title, Member owner) {
    }

    private final DonationOrderRepository orders;
    private final RecordedBroadcastRepository recorded;
    private final LiveBroadcastRepository live;
    private final PaymentGateway gateway;
    private final EntityManager entityManager;

    DonationService(DonationOrderRepository orders, RecordedBroadcastRepository recorded,
                    LiveBroadcastRepository live, PaymentGateway gateway, EntityManager entityManager) {
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
        Broadcast broadcast = find(kind, broadcastId).orElseThrow(DonationBroadcastNotFoundException::new);
        if (broadcast.owner() == null) {
            throw new InvalidDonationException("소유자가 없는 방송에는 후원할 수 없습니다.");
        }
        if (broadcast.owner().getId().equals(donorId)) {
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
        DonationOrder order = orders.save(new DonationOrder(UUID.randomUUID().toString(), kind, broadcastId,
                donor, broadcast.owner(), amount, validMessage));
        return new OrderInfo(order.getOrderId(), amount, orderName(broadcast.title()));
    }

    /**
     * 승인 통지(성공 리다이렉트·웹훅)를 확정한다. 이미 확정된 주문이면 같은 결과를 돌려주고 아무것도 바꾸지 않는다.
     * {@code redirectAmount}가 있으면(성공 리다이렉트) 주문 금액과 비교한 뒤 결제사 승인 API를, 없으면(웹훅)
     * 조회 API를 쓴다. 결제사 응답(상태 DONE·금액·주문 번호)과 방송·소유자(=수신자)를 확인한 뒤에만 확정하고,
     * 승인된 결제가 검증에 실패하면 결제사에 취소를 요청한다.
     *
     * @throws DonationOrderNotFoundException 없는 주문
     * @throws DonationRejectedException 확정하지 못함
     */
    public Confirmed confirm(String paymentKey, String orderId, Long redirectAmount) {
        DonationOrder order = orders.findWithDonor(orderId).orElseThrow(DonationOrderNotFoundException::new);
        if (order.getStatus() == DonationStatus.CONFIRMED) {
            return confirmed(order);
        }
        if (order.getStatus() != DonationStatus.PENDING) {
            throw rejected(order, "이미 처리가 끝난 후원 주문입니다.");
        }
        if (redirectAmount != null && redirectAmount != order.getAmount()) {
            throw fail(order, "결제 금액이 후원 주문의 금액과 달라 후원을 확정하지 않았습니다.");
        }

        PaymentResult result = approve(order, paymentKey, redirectAmount != null);
        String problem = problem(order, result);
        if (problem != null) {
            if (result.isDone()) {
                cancelQuietly(paymentKey, "후원 확정 검증 실패");
            }
            throw fail(order, problem);
        }

        int updated;
        try {
            updated = orders.confirmIfPending(orderId, paymentKey, Instant.now());
        } catch (DataIntegrityViolationException e) {
            cancelQuietly(paymentKey, "이미 다른 후원에 쓰인 결제");
            throw fail(order, "이미 다른 후원에 쓰인 결제입니다.");
        }
        if (updated == 0) {
            DonationOrder current = orders.findWithDonor(orderId).orElseThrow(DonationOrderNotFoundException::new);
            if (current.getStatus() == DonationStatus.CONFIRMED) {
                return confirmed(current);
            }
            throw rejected(current, "이미 처리가 끝난 후원 주문입니다.");
        }
        return confirmed(order);
    }

    /**
     * 결제창 실패·취소 리다이렉트를 주문에 반영한다. 대기 상태일 때만 바꾸고(취소면 CANCELED, 그 밖은 FAILED),
     * 이미 처리가 끝난 주문은 그대로 둔다(확정된 주문은 확정 유지).
     *
     * @throws DonationOrderNotFoundException 없는 주문
     */
    public Ended recordFailure(String orderId, String code, String message) {
        DonationOrder order = orders.findWithDonor(orderId).orElseThrow(DonationOrderNotFoundException::new);
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
                order.getMessage());
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
        return find(kind, broadcastId)
                .map(Broadcast::owner)
                .map(owner -> !owner.getId().equals(memberId))
                .orElse(false);
    }

    private PaymentResult approve(DonationOrder order, String paymentKey, boolean viaConfirm) {
        try {
            if (!viaConfirm) {
                return gateway.query(paymentKey);
            }
            try {
                return gateway.confirm(paymentKey, order.getOrderId(), order.getAmount());
            } catch (PaymentGatewayException e) {
                if (ALREADY_PROCESSED.equals(e.code())) {
                    return gateway.query(paymentKey);
                }
                throw e;
            }
        } catch (PaymentGatewayException e) {
            log.warn("결제사 호출 실패 order={} code={}", order.getOrderId(), e.code());
            throw fail(order, "결제 승인을 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    /** 결제사 응답과 방송 상태가 주문과 맞는지 확인한다. 맞으면 null, 아니면 사용자에게 보일 사유. */
    private String problem(DonationOrder order, PaymentResult result) {
        if (!result.isDone()) {
            return "결제가 완료되지 않아 후원을 확정하지 않았습니다.";
        }
        if (result.totalAmount() != order.getAmount() || !order.getOrderId().equals(result.orderId())) {
            return "결제 정보가 후원 주문과 달라 후원을 확정하지 않았습니다.";
        }
        Optional<Broadcast> broadcast = find(order.getKind(), order.getBroadcastId());
        if (broadcast.isEmpty()) {
            return "후원할 방송이 더는 없어 후원을 확정하지 않았습니다.";
        }
        Member owner = broadcast.get().owner();
        if (owner == null || !owner.getId().equals(order.getRecipient().getId())) {
            return "방송 소유자가 바뀌어 후원을 확정하지 않았습니다.";
        }
        return null;
    }

    private DonationRejectedException fail(DonationOrder order, String reason) {
        orders.failIfPending(order.getOrderId(), reason);
        return rejected(order, reason);
    }

    private static DonationRejectedException rejected(DonationOrder order, String reason) {
        return new DonationRejectedException(reason, order.getKind(), order.getBroadcastId());
    }

    private void cancelQuietly(String paymentKey, String reason) {
        try {
            gateway.cancel(paymentKey, reason);
        } catch (RuntimeException e) {
            log.error("결제 취소 실패 paymentKey={}", paymentKey, e);
        }
    }

    private static Confirmed confirmed(DonationOrder order) {
        return new Confirmed(order.getKind(), order.getBroadcastId(), order.getAmount(), order.getMessage());
    }

    private static String orderName(String title) {
        String name = title + " 후원";
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private Optional<Broadcast> find(DonationKind kind, Long id) {
        return switch (kind) {
            case RECORDED -> recorded.findById(id).map(b -> new Broadcast(b.getTitle(), b.getOwner()));
            case LIVE -> live.findById(id).map(b -> new Broadcast(b.getTitle(), b.getOwner()));
        };
    }
}
