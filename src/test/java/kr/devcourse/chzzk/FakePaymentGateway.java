package kr.devcourse.chzzk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import kr.devcourse.chzzk.donation.PaymentGateway;
import kr.devcourse.chzzk.donation.PaymentResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 테스트용 결제사. 호출을 기록하고, 테스트가 응답을 바꿀 수 있다.
 * 기본 동작은 요청한 금액·주문 번호 그대로 DONE을 돌려준다. 사용: {@code @Import(FakePaymentGateway.Config.class)}.
 */
public class FakePaymentGateway implements PaymentGateway {

    public record Confirm(String paymentKey, String orderId, long amount) {
    }

    public record Cancel(String paymentKey, String reason) {
    }

    private static final Function<Confirm, PaymentResult> DONE =
            c -> new PaymentResult("DONE", c.amount(), c.orderId(), c.paymentKey());
    private static final Function<String, PaymentResult> NO_QUERY = k -> {
        throw new IllegalStateException("조회 응답이 설정되지 않았습니다.");
    };

    public final List<Confirm> confirms = new ArrayList<>();
    public final List<String> queries = new ArrayList<>();
    public final List<Cancel> cancels = new ArrayList<>();

    /** 승인 응답을 만드는 함수. 예외를 던지면 그대로 호출자에게 전달된다. */
    public volatile Function<Confirm, PaymentResult> onConfirm = DONE;
    /** 조회 응답을 만드는 함수. */
    public volatile Function<String, PaymentResult> onQuery = NO_QUERY;

    public synchronized void reset() {
        confirms.clear();
        queries.clear();
        cancels.clear();
        onConfirm = DONE;
        onQuery = NO_QUERY;
    }

    @Override
    public PaymentResult confirm(String paymentKey, String orderId, long amount) {
        Confirm call = new Confirm(paymentKey, orderId, amount);
        synchronized (this) {
            confirms.add(call);
        }
        return onConfirm.apply(call);
    }

    @Override
    public PaymentResult query(String paymentKey) {
        synchronized (this) {
            queries.add(paymentKey);
        }
        return onQuery.apply(paymentKey);
    }

    @Override
    public synchronized void cancel(String paymentKey, String reason) {
        cancels.add(new Cancel(paymentKey, reason));
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakePaymentGateway fakePaymentGateway() {
            return new FakePaymentGateway();
        }
    }
}
