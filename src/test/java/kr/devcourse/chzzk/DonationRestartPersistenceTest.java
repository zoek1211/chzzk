package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** 같은 데이터 디렉터리로 다시 띄워도 확정된 후원과 합계가 남아 있고, 대기 주문은 합계에 들어가지 않는지. */
class DonationRestartPersistenceTest {

    @TempDir
    Path dataDir;

    @Test
    void 재시작한_뒤에도_확정된_후원과_합계가_남아_있다() throws Exception {
        String detailUrl;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = TestWeb.mvc((WebApplicationContext) first);
            detailUrl = mvc.perform(post("/broadcasts").session(MemberSessions.signUpAs(mvc, "방송주인"))
                            .param("title", "재시작 후원 방송").param("videoUrl", "https://example.com/v.mp4"))
                    .andReturn().getResponse().getRedirectedUrl();
            String broadcastId = detailUrl.substring(detailUrl.lastIndexOf('/') + 1);
            MockHttpSession donor = MemberSessions.signUpAs(mvc, "남는후원자");

            String confirmedOrder = orderOf(mvc, donor, broadcastId, 5000, "재시작해도 남는 후원");
            orderOf(mvc, donor, broadcastId, 9000, "결제하지 않은 주문");
            mvc.perform(get("/donations/success").session(donor).param("paymentKey", "pk_restart")
                            .param("orderId", confirmedOrder).param("amount", "5000"))
                    .andExpect(status().isOk());
        }

        try (ConfigurableApplicationContext second = start()) {
            String html = TestWeb.mvc((WebApplicationContext) second).perform(get(detailUrl))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("재시작해도 남는 후원").contains("남는후원자").contains("5,000원")
                    .doesNotContain("결제하지 않은 주문").doesNotContain("9,000");
        }
    }

    private static String orderOf(MockMvc mvc, MockHttpSession session, String broadcastId, long amount,
                                  String message) throws Exception {
        String json = mvc.perform(post("/donations").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RECORDED\",\"broadcastId\":" + broadcastId + ",\"amount\":" + amount
                                + ",\"message\":\"" + message + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.orderId");
    }

    private ConfigurableApplicationContext start() {
        // 커맨드라인 인자로 넘겨야 application.properties 의 기본값보다 우선한다. 키는 테스트용 자리표시자다.
        return new SpringApplicationBuilder(ChzzkApplication.class, FakePaymentGateway.Config.class)
                .run("--chzzk.data-dir=" + dataDir, "--server.port=0",
                        "--chzzk.toss.client-key=test_ck_placeholder", "--chzzk.toss.secret-key=test_sk_placeholder");
    }
}
