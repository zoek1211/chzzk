package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
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

/** 결제창 실패·취소 리다이렉트가 주문을 한 번만 닫고 합계에 들어가지 않는지 HTTP 경계에서 검증한다. */
@SpringBootTest
@Import(FakePaymentGateway.Config.class)
class DonationFailWebTest {

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

    MockMvc mvc;
    String broadcastId;
    String orderId;
    MockHttpSession donor;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestWeb.mvc(context);
        gateway.reset();
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "방송주인");
        String url = mvc.perform(post("/broadcasts").session(owner).param("title", "녹화")
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        broadcastId = url.substring(url.lastIndexOf('/') + 1);
        donor = MemberSessions.signUpAs(mvc, "후원자");
        String json = mvc.perform(post("/donations").session(donor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RECORDED\",\"broadcastId\":" + broadcastId + ",\"amount\":5000,\"message\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        orderId = JsonPath.read(json, "$.orderId");
    }

    String page() throws Exception {
        return mvc.perform(get("/broadcasts/" + broadcastId)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void 구매자가_취소하면_취소_화면과_다시_시도_링크를_보이고_적립되지_않는다() throws Exception {
        mvc.perform(get("/donations/fail").session(donor).param("code", "PAY_PROCESS_CANCELED").param("message", "취소됨")
                        .param("orderId", orderId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("결제를 취소해 후원하지 않았습니다.")))
                .andExpect(content().string(containsString("/broadcasts/" + broadcastId)))
                .andExpect(content().string(containsString("다시 시도하기")));
        assertThat(page()).doesNotContain("5,000");
    }

    @Test
    void 그_밖의_실패_코드는_실패_화면이고_적립되지_않는다() throws Exception {
        mvc.perform(get("/donations/fail").session(donor).param("code", "REJECT_CARD_COMPANY").param("message", "한도 초과")
                        .param("orderId", orderId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("결제에 실패해 후원하지 않았습니다.")));
        assertThat(page()).doesNotContain("5,000");
    }

    @Test
    void 이미_닫힌_주문은_다시_바뀌지_않아_뒤늦은_성공_리다이렉트도_거절된다() throws Exception {
        mvc.perform(get("/donations/fail").session(donor).param("code", "PAY_PROCESS_CANCELED").param("orderId", orderId))
                .andExpect(status().isOk());
        mvc.perform(get("/donations/fail").session(donor).param("code", "REJECT_CARD_COMPANY").param("orderId", orderId))
                .andExpect(content().string(containsString("결제를 취소해 후원하지 않았습니다.")));
        mvc.perform(get("/donations/success").session(donor).param("paymentKey", "pk_f").param("orderId", orderId).param("amount", "5000"))
                .andExpect(status().isBadRequest());
        assertThat(gateway.confirms).isEmpty();
        assertThat(page()).doesNotContain("5,000");
    }

    @Test
    void 이미_확정된_주문에_실패_리다이렉트가_와도_확정이_유지된다() throws Exception {
        mvc.perform(get("/donations/success").session(donor).param("paymentKey", "pk_ok").param("orderId", orderId).param("amount", "5000"))
                .andExpect(status().isOk());
        mvc.perform(get("/donations/fail").session(donor).param("code", "PAY_PROCESS_CANCELED").param("orderId", orderId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("후원이 완료되었습니다")));
        assertThat(page()).contains("5,000");
    }

    @Test
    void 없는_주문은_404이고_orderId가_없으면_400() throws Exception {
        mvc.perform(get("/donations/fail").session(donor).param("code", "X").param("orderId", "nope"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/donations/fail").session(donor).param("code", "X")).andExpect(status().isBadRequest());
    }
}
