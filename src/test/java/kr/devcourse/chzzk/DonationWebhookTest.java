package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.devcourse.chzzk.donation.DonationKind;
import kr.devcourse.chzzk.donation.DonationService;
import kr.devcourse.chzzk.donation.PaymentGatewayException;
import kr.devcourse.chzzk.donation.PaymentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** 웹훅 경로와 중복 확정 방지를 검증한다(결제사는 가짜). */
@SpringBootTest
@Import(FakePaymentGateway.Config.class)
class DonationWebhookTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    FakePaymentGateway gateway;
    @Autowired
    DonationService donations;

    MockMvc mvc;
    Long broadcastId;
    MockHttpSession donor;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestWeb.mvc(context);
        gateway.reset();
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "방송주인");
        String url = mvc.perform(post("/broadcasts").session(owner).param("title", "녹화")
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        broadcastId = Long.valueOf(url.substring(url.lastIndexOf('/') + 1));
        donor = MemberSessions.signUpAs(mvc, "후원자");
    }

    String order(long amount) throws Exception {
        String json = mvc.perform(post("/donations").session(donor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RECORDED\",\"broadcastId\":" + broadcastId + ",\"amount\":" + amount
                                + ",\"message\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.orderId");
    }

    void queryReturns(String status, String orderId, long amount) {
        gateway.onQuery = key -> new PaymentResult(status, amount, orderId, key);
    }

    void webhook(String paymentKey, String orderId) throws Exception {
        MockMvc noCsrf = TestWeb.mvcWithoutCsrf(context);
        noCsrf.perform(post("/donations/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"" + paymentKey
                                + "\",\"orderId\":\"" + orderId + "\",\"status\":\"DONE\"}}"))
                .andExpect(status().isOk());
    }

    void successRedirect(String paymentKey, String orderId, long amount) throws Exception {
        mvc.perform(get("/donations/success").param("paymentKey", paymentKey).param("orderId", orderId)
                .param("amount", String.valueOf(amount)));
    }

    long total() {
        return donations.total(DonationKind.RECORDED, broadcastId);
    }

    @Test
    void 웹훅만으로_대기_주문이_확정된다() throws Exception {
        String orderId = order(5000);
        queryReturns("DONE", orderId, 5000);
        webhook("pk-1", orderId);
        assertThat(total()).isEqualTo(5000);
        assertThat(gateway.queries).containsExactly("pk-1");
        assertThat(gateway.confirms).isEmpty();
    }

    @Test
    void 같은_웹훅을_두_번_받아도_한_번만_확정된다() throws Exception {
        String orderId = order(5000);
        queryReturns("DONE", orderId, 5000);
        webhook("pk-2", orderId);
        webhook("pk-2", orderId);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 웹훅_뒤_성공_리다이렉트도_한_번만_확정된다() throws Exception {
        String orderId = order(5000);
        queryReturns("DONE", orderId, 5000);
        webhook("pk-3", orderId);
        successRedirect("pk-3", orderId, 5000);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 성공_리다이렉트_뒤_웹훅도_한_번만_확정된다() throws Exception {
        String orderId = order(5000);
        queryReturns("DONE", orderId, 5000);
        successRedirect("pk-4", orderId, 5000);
        webhook("pk-4", orderId);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 성공_리다이렉트를_두_번_해도_한_번만_확정된다() throws Exception {
        String orderId = order(5000);
        successRedirect("pk-5", orderId, 5000);
        successRedirect("pk-5", orderId, 5000);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 같은_주문을_여러_스레드가_동시에_확정해도_한_번만_확정된다() throws Exception {
        String orderId = order(7000);
        queryReturns("DONE", orderId, 7000);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Long amount = i % 2 == 0 ? null : 7000L;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    donations.confirm("pk-6", orderId, amount);
                } catch (RuntimeException ignored) {
                    // 동시 호출 중 일부는 거절될 수 있다. 합계로 확인한다.
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(total()).isEqualTo(7000);
        assertThat(donations.list(DonationKind.RECORDED, broadcastId)).hasSize(1);
    }

    @Test
    void 조회_결과가_DONE이_아니면_확정하지_않고_이후_통지로_확정할_수_있다() throws Exception {
        String orderId = order(5000);
        queryReturns("WAITING_FOR_DEPOSIT", orderId, 5000);
        webhook("pk-7", orderId);
        assertThat(total()).isZero();
        queryReturns("DONE", orderId, 5000);
        webhook("pk-7", orderId);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 모르는_주문_본문_오류_금액_불일치_웹훅은_200이고_아무것도_바꾸지_않는다() throws Exception {
        String orderId = order(5000);
        queryReturns("DONE", orderId, 5000);
        webhook("pk-8", "unknown-order");
        MockMvc noCsrf = TestWeb.mvcWithoutCsrf(context);
        noCsrf.perform(post("/donations/webhook").contentType(MediaType.APPLICATION_JSON).content("{깨진"))
                .andExpect(status().isOk());
        noCsrf.perform(post("/donations/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        queryReturns("DONE", orderId, 9000);
        webhook("pk-8", orderId);
        assertThat(total()).isZero();
        queryReturns("DONE", orderId, 5000);
        webhook("pk-8", orderId);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 승인_API가_이미_처리됨이면_조회로_확인해_확정한다() throws Exception {
        String orderId = order(5000);
        gateway.onConfirm = c -> {
            throw new PaymentGatewayException("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제입니다.");
        };
        queryReturns("DONE", orderId, 5000);
        successRedirect("pk-9", orderId, 5000);
        assertThat(total()).isEqualTo(5000);
        assertThat(gateway.queries).containsExactly("pk-9");
    }
}
