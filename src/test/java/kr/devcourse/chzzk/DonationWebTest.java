package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
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

/** 후원 주문 생성과 성공 리다이렉트 확정, 방송 화면 표시를 HTTP 경계에서 검증한다(결제사는 가짜). */
@SpringBootTest
@Import(FakePaymentGateway.Config.class)
class DonationWebTest {

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

    @BeforeEach
    void setUp() {
        mvc = TestWeb.mvc(context);
        gateway.reset();
    }

    String recordedBy(MockHttpSession owner) throws Exception {
        String url = mvc.perform(post("/broadcasts").session(owner).param("title", "녹화")
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    String liveBy(MockHttpSession owner) throws Exception {
        String json = mvc.perform(post("/live").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"라이브\"}"))
                .andReturn().getResponse().getContentAsString();
        return String.valueOf((Object) JsonPath.read(json, "$.id"));
    }

    String body(String kind, String id, long amount, String message) {
        return "{\"kind\":\"" + kind + "\",\"broadcastId\":" + id + ",\"amount\":" + amount
                + ",\"message\":\"" + message + "\"}";
    }

    String order(MockHttpSession session, String kind, String id, long amount, String message) throws Exception {
        String json = mvc.perform(post("/donations").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(kind, id, amount, message)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.orderId");
    }

    String page(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void 주문을_만들면_201로_결제창에_필요한_정보를_준다() throws Exception {
        String id = recordedBy(MemberSessions.signUpAs(mvc, "방송주인"));
        mvc.perform(post("/donations").session(MemberSessions.signUpAs(mvc, "후원자"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("RECORDED", id, 5000, "힘내세요")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.orderName").value("녹화 후원"))
                .andExpect(jsonPath("$.clientKey").exists())
                .andExpect(jsonPath("$.successUrl").value("http://localhost/donations/success"))
                .andExpect(jsonPath("$.failUrl").value("http://localhost/donations/fail"));
    }

    @Test
    void 비로그인은_401이고_CSRF가_없으면_거절된다() throws Exception {
        String id = recordedBy(MemberSessions.signUpAs(mvc, "방송주인"));
        mvc.perform(post("/donations").contentType(MediaType.APPLICATION_JSON).content(body("RECORDED", id, 5000, "")))
                .andExpect(status().isUnauthorized());
        MockMvc noCsrf = TestWeb.mvcWithoutCsrf(context);
        noCsrf.perform(post("/donations").session(MemberSessions.signUpAs(mvc, "후원자"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("RECORDED", id, 5000, "")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_방송은_404() throws Exception {
        mvc.perform(post("/donations").session(MemberSessions.signUpAs(mvc, "후원자"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("LIVE", "999999", 5000, "")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 규칙을_어기면_400과_한국어_메시지() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "방송주인");
        String id = recordedBy(owner);
        MockHttpSession donor = MemberSessions.signUpAs(mvc, "후원자");
        for (long amount : new long[]{500, 1500, 1_001_000, 0, -1000}) {
            mvc.perform(post("/donations").session(donor).contentType(MediaType.APPLICATION_JSON)
                            .content(body("RECORDED", id, amount, "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("후원 금액은 1,000원 단위로 1,000원 이상 1,000,000원 이하여야 합니다."));
        }
        mvc.perform(post("/donations").session(donor).contentType(MediaType.APPLICATION_JSON)
                        .content(body("RECORDED", id, 1000, "가".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("후원 메시지는 100자 이하로 입력해 주세요."));
        mvc.perform(post("/donations").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content(body("RECORDED", id, 1000, "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("자기 방송에는 후원할 수 없습니다."));
        mvc.perform(post("/donations").session(donor).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 비로그인_방문자는_후원_영역에서_로그인_링크를_본다() throws Exception {
        String id = recordedBy(MemberSessions.signUpAs(mvc, "방송주인"));
        assertThat(page("/broadcasts/" + id)).contains("로그인 후 후원");
    }

    @Test
    void 성공_리다이렉트로_확정되면_방송_화면의_합계와_내역에_반영된다() throws Exception {
        String id = recordedBy(MemberSessions.signUpAs(mvc, "방송주인"));
        String orderId = order(MemberSessions.signUpAs(mvc, "후원자"), "RECORDED", id, 5000, "<b>힘내세요</b>");

        mvc.perform(get("/donations/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "5000"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/broadcasts/" + id)));
        assertThat(gateway.confirms).hasSize(1);

        String detail = page("/broadcasts/" + id);
        assertThat(detail).contains("5,000").contains("후원자").contains("&lt;b&gt;힘내세요&lt;/b&gt;")
                .doesNotContain("<b>힘내세요</b>");

        mvc.perform(get("/donations/success").param("paymentKey", "pk_1").param("orderId", orderId).param("amount", "5000"))
                .andExpect(status().isOk());
        assertThat(gateway.confirms).hasSize(1);
    }

    @Test
    void 라이브_시청_화면에도_후원_합계가_나온다() throws Exception {
        String id = liveBy(MemberSessions.signUpAs(mvc, "방송주인"));
        String orderId = order(MemberSessions.signUpAs(mvc, "후원자"), "LIVE", id, 1000, "");
        mvc.perform(get("/donations/success").param("paymentKey", "pk_2").param("orderId", orderId).param("amount", "1000"))
                .andExpect(status().isOk());
        assertThat(page("/live/" + id)).contains("후원 합계").contains("1,000");
    }

    @Test
    void 확정_전에는_합계에_들어가지_않는다() throws Exception {
        String id = recordedBy(MemberSessions.signUpAs(mvc, "방송주인"));
        order(MemberSessions.signUpAs(mvc, "후원자"), "RECORDED", id, 5000, "");
        assertThat(page("/broadcasts/" + id)).doesNotContain("5,000");
    }

    @Test
    void 금액을_바꾼_주소는_실패하고_적립되지_않는다() throws Exception {
        String id = recordedBy(MemberSessions.signUpAs(mvc, "방송주인"));
        String orderId = order(MemberSessions.signUpAs(mvc, "후원자"), "RECORDED", id, 5000, "");
        mvc.perform(get("/donations/success").param("paymentKey", "pk_3").param("orderId", orderId).param("amount", "1000"))
                .andExpect(status().isBadRequest());
        assertThat(gateway.confirms).isEmpty();
        assertThat(page("/broadcasts/" + id)).doesNotContain("5,000");
    }

    @Test
    void 승인_뒤_응답이_어긋나면_결제를_취소하고_적립하지_않는다() throws Exception {
        String id = recordedBy(MemberSessions.signUpAs(mvc, "방송주인"));
        String orderId = order(MemberSessions.signUpAs(mvc, "후원자"), "RECORDED", id, 5000, "");
        gateway.onConfirm = c -> new PaymentResult("DONE", 1000, c.orderId(), c.paymentKey());
        mvc.perform(get("/donations/success").param("paymentKey", "pk_4").param("orderId", orderId).param("amount", "5000"))
                .andExpect(status().isBadRequest());
        assertThat(gateway.cancels).extracting(FakePaymentGateway.Cancel::paymentKey).containsExactly("pk_4");
        assertThat(page("/broadcasts/" + id)).doesNotContain("5,000");
    }

    @Test
    void 없는_주문은_404() throws Exception {
        mvc.perform(get("/donations/success").param("paymentKey", "pk").param("orderId", "nope").param("amount", "1000"))
                .andExpect(status().isNotFound());
    }
}
