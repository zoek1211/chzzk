package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import kr.devcourse.chzzk.donation.DonationKind;
import kr.devcourse.chzzk.donation.DonationService;
import kr.devcourse.chzzk.donation.PaymentGatewayException;
import kr.devcourse.chzzk.donation.PaymentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** 남의 결제 취소 금지, 일시 오류 처리, 후원자 본인만 리다이렉트 처리, 화면 오류 문구를 검증한다(결제사는 가짜). */
@SpringBootTest
@Import(FakePaymentGateway.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class DonationSafetyWebTest {

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
    /** 클래스 안에서 결제 키는 유니크해야 해서 테스트마다 새로 만든다. */
    String victimKey;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestWeb.mvc(context);
        gateway.reset();
        victimKey = "pk-victim-" + java.util.UUID.randomUUID();
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "방송주인");
        String url = mvc.perform(post("/broadcasts").session(owner).param("title", "녹화")
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        broadcastId = Long.valueOf(url.substring(url.lastIndexOf('/') + 1));
        donor = MemberSessions.signUpAs(mvc, "후원자");
    }

    String order(MockHttpSession session, long amount) throws Exception {
        String json = mvc.perform(post("/donations").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RECORDED\",\"broadcastId\":" + broadcastId + ",\"amount\":" + amount
                                + ",\"message\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.orderId");
    }

    String order(long amount) throws Exception {
        return order(donor, amount);
    }

    void success(MockHttpSession session, String paymentKey, String orderId, long amount, int expectedStatus)
            throws Exception {
        mvc.perform(get("/donations/success").session(session).param("paymentKey", paymentKey)
                        .param("orderId", orderId).param("amount", String.valueOf(amount)))
                .andExpect(status().is(expectedStatus));
    }

    void webhook(String paymentKey, String orderId) throws Exception {
        TestWeb.mvcWithoutCsrf(context).perform(post("/donations/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId + "\"}}"))
                .andExpect(status().isOk());
    }

    void queryReturns(String status, String orderId, long amount) {
        gateway.onQuery = key -> new PaymentResult(status, amount, orderId, key);
    }

    void confirmFails(String code) {
        gateway.onConfirm = c -> {
            throw new PaymentGatewayException(code, "결제사 오류");
        };
    }

    void queryFails() {
        gateway.onQuery = k -> {
            throw new PaymentGatewayException("NETWORK_ERROR", "연결 실패");
        };
    }

    long total() {
        return donations.total(DonationKind.RECORDED, broadcastId);
    }

    /** 피해자가 5000원을 확정해 둔 상태를 만들고 그 주문 번호를 돌려준다. */
    String victimConfirmed() throws Exception {
        MockHttpSession victim = MemberSessions.signUpAs(mvc, "피해자");
        String victimOrder = order(victim, 5000);
        success(victim, victimKey, victimOrder, 5000, 200);
        assertThat(total()).isEqualTo(5000);
        gateway.reset();
        return victimOrder;
    }

    // --- 남의 결제는 취소하지 않는다 ---

    @Test
    void 성공_리다이렉트에서_남의_결제_키를_내밀어도_그_결제를_취소하지_않는다() throws Exception {
        String victimOrder = victimConfirmed();
        String attackerOrder = order(5000);
        gateway.onConfirm = c -> new PaymentResult("DONE", 5000, victimOrder, c.paymentKey());
        success(donor, victimKey, attackerOrder, 5000, 400);
        assertThat(gateway.cancels).isEmpty();
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 승인이_실패한_뒤_조회가_남의_결제를_돌려줘도_취소하지_않는다() throws Exception {
        String victimOrder = victimConfirmed();
        String attackerOrder = order(5000);
        confirmFails("ALREADY_PROCESSED_PAYMENT");
        queryReturns("DONE", victimOrder, 5000);
        success(donor, victimKey, attackerOrder, 5000, 400);
        assertThat(gateway.cancels).isEmpty();
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 웹훅으로_남의_결제_키를_보내도_취소하지_않고_주문도_바꾸지_않는다() throws Exception {
        String victimOrder = victimConfirmed();
        String attackerOrder = order(5000);
        queryReturns("DONE", victimOrder, 5000);
        webhook(victimKey, attackerOrder);
        assertThat(gateway.cancels).isEmpty();
        assertThat(total()).isEqualTo(5000);
        // 주문은 대기로 남아 진짜 결제로 확정할 수 있다
        success(donor, "pk-mine", attackerOrder, 5000, 200);
        assertThat(total()).isEqualTo(10000);
    }

    @Test
    void 웹훅은_이_주문의_결제가_금액이_달라도_취소하지_않는다() throws Exception {
        String orderId = order(5000);
        queryReturns("DONE", orderId, 9000);
        webhook("pk-w", orderId);
        assertThat(gateway.cancels).isEmpty();
        assertThat(total()).isZero();
        queryReturns("DONE", orderId, 5000);
        webhook("pk-w", orderId);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 이미_다른_후원에_쓰인_결제_키는_취소하지_않는다() throws Exception {
        victimConfirmed();
        String attackerOrder = order(5000);
        // 승인 응답은 이 주문의 것이지만 결제 키가 이미 다른 후원에 쓰였다
        success(donor, victimKey, attackerOrder, 5000, 400);
        assertThat(gateway.cancels).isEmpty();
        assertThat(total()).isEqualTo(5000);
    }

    // --- 일시 오류는 주문을 실패로 만들지 않는다 ---

    @Test
    void 승인_호출이_실패해도_조회가_DONE이면_확정한다() throws Exception {
        String orderId = order(5000);
        confirmFails("NETWORK_ERROR");
        queryReturns("DONE", orderId, 5000);
        success(donor, "pk-t1", orderId, 5000, 200);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 승인도_조회도_실패하면_대기로_두고_지연_안내를_보이며_뒤늦은_웹훅이_확정한다() throws Exception {
        String orderId = order(5000);
        confirmFails("NETWORK_ERROR");
        queryFails();
        mvc.perform(get("/donations/success").session(donor).param("paymentKey", "pk-t2").param("orderId", orderId)
                        .param("amount", "5000"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(containsString("결제 확인이 지연되고 있으며 결제가 확인되면 후원이 반영됩니다.")));
        assertThat(total()).isZero();
        queryReturns("DONE", orderId, 5000);
        webhook("pk-t2", orderId);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 조회가_아직_끝나지_않은_상태이면_대기로_둔다() throws Exception {
        String orderId = order(5000);
        confirmFails("PROVIDER_ERROR");
        queryReturns("IN_PROGRESS", orderId, 5000);
        success(donor, "pk-t3", orderId, 5000, 202);
        queryReturns("DONE", orderId, 5000);
        webhook("pk-t3", orderId);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 조회가_끝난_비완료_상태이면_실패로_닫아_뒤늦은_웹훅도_확정하지_않는다() throws Exception {
        String orderId = order(5000);
        confirmFails("PROVIDER_ERROR");
        queryReturns("ABORTED", orderId, 5000);
        success(donor, "pk-t4", orderId, 5000, 400);
        queryReturns("DONE", orderId, 5000);
        webhook("pk-t4", orderId);
        assertThat(total()).isZero();
    }

    @Test
    void 카드_거절_코드는_조회_없이_실패로_닫는다() throws Exception {
        String orderId = order(5000);
        confirmFails("REJECT_CARD_COMPANY");
        success(donor, "pk-t5", orderId, 5000, 400);
        assertThat(gateway.queries).isEmpty();
        queryReturns("DONE", orderId, 5000);
        webhook("pk-t5", orderId);
        assertThat(total()).isZero();
    }

    @Test
    void 로그에_결제_키_전체를_남기지_않는다(CapturedOutput output) throws Exception {
        String orderId = order(5000);
        confirmFails("NETWORK_ERROR");
        queryFails();
        success(donor, "pk-secret-abcdef123456", orderId, 5000, 202);
        assertThat(output.getAll()).doesNotContain("pk-secret-abcdef123456");
    }

    // --- 성공·실패 리다이렉트는 후원자 본인만 ---

    @Test
    void 비로그인이_성공_실패_주소를_열면_로그인으로_보낸다() throws Exception {
        String orderId = order(5000);
        mvc.perform(get("/donations/success").param("paymentKey", "pk").param("orderId", orderId)
                        .param("amount", "5000"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/donations/fail").param("code", "X").param("orderId", orderId))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        assertThat(gateway.confirms).isEmpty();
    }

    @Test
    void 다른_회원은_성공_실패_주소에서_403이고_주문은_그대로다() throws Exception {
        String orderId = order(5000);
        MockHttpSession stranger = MemberSessions.signUpAs(mvc, "낯선사람");
        mvc.perform(get("/donations/success").session(stranger).param("paymentKey", "pk-s").param("orderId", orderId)
                        .param("amount", "1000"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("후원한 본인만 결제 결과를 확인할 수 있습니다.")));
        mvc.perform(get("/donations/fail").session(stranger).param("code", "PAY_PROCESS_CANCELED")
                        .param("orderId", orderId))
                .andExpect(status().isForbidden());
        assertThat(gateway.confirms).isEmpty();
        success(donor, "pk-s", orderId, 5000, 200);
        assertThat(total()).isEqualTo(5000);
    }

    @Test
    void 다른_회원에게_확정된_주문의_금액과_메시지를_보이지_않는다() throws Exception {
        String orderId = order(5000);
        success(donor, "pk-s2", orderId, 5000, 200);
        MockHttpSession stranger = MemberSessions.signUpAs(mvc, "낯선사람");
        mvc.perform(get("/donations/fail").session(stranger).param("orderId", orderId))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("5,000"))));
    }

    // --- 실패 화면 사유 ---

    @Test
    void 실패_화면은_결제사_메시지를_이스케이프해_보이고_취소에는_보이지_않는다() throws Exception {
        String orderId = order(5000);
        mvc.perform(get("/donations/fail").session(donor).param("code", "REJECT_CARD_COMPANY")
                        .param("message", "<b>한도 초과</b>").param("orderId", orderId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("결제에 실패해 후원하지 않았습니다.")))
                .andExpect(content().string(containsString("&lt;b&gt;한도 초과&lt;/b&gt;")));
        String canceled = order(1000);
        mvc.perform(get("/donations/fail").session(donor).param("code", "PAY_PROCESS_CANCELED")
                        .param("message", "사용자 취소 메시지").param("orderId", canceled))
                .andExpect(content().string(containsString("결제를 취소해 후원하지 않았습니다.")))
                .andExpect(content().string(not(containsString("사용자 취소 메시지"))));
    }

    // --- 화면 경로의 오류 응답 ---

    @Test
    void 화면_경로의_404_400은_한국어_문장을_보인다() throws Exception {
        mvc.perform(get("/donations/success").session(donor).param("paymentKey", "pk").param("orderId", "nope")
                        .param("amount", "1000"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("후원 주문을 찾을 수 없습니다.")));
        mvc.perform(get("/donations/fail").session(donor).param("code", "X"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("요청 정보가 올바르지 않습니다.")));
        mvc.perform(get("/donations/success").session(donor).param("paymentKey", "pk").param("orderId", "o")
                        .param("amount", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("요청 정보가 올바르지 않습니다.")));
    }
}
