package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** 토스 키 중 하나가 비어 있어도 앱이 뜨고, 주문은 503, 후원 영역은 안내만 보인다(합계·내역은 유지). */
@SpringBootTest(properties = {"chzzk.toss.client-key=", "chzzk.toss.secret-key=test_sk_never_shown"})
class DonationDisabledWebTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @Autowired
    WebApplicationContext context;

    @Test
    void 클라이언트_키가_비면_주문_생성은_한국어_메시지와_함께_503이다() throws Exception {
        MockMvc mvc = TestWeb.mvc(context);
        MockHttpSession donor = MemberSessions.signUpAs(mvc, "후원자");
        mvc.perform(post("/donations").session(donor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RECORDED\",\"broadcastId\":1,\"amount\":5000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("결제 키가 설정되지 않아 후원을 받을 수 없습니다."));
    }

    @Test
    void 방송_화면은_후원_폼_대신_안내와_합계를_보여주고_비밀_키는_내지_않는다() throws Exception {
        MockMvc mvc = TestWeb.mvc(context);
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "방송주인");
        String url = mvc.perform(post("/broadcasts").session(owner).param("title", "녹화")
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        String html = mvc.perform(get(url).session(owner)).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("결제 키가 설정되지 않아 후원을 받을 수 없습니다", "후원 합계");
        assertThat(html).doesNotContain("donation-form", "donation-submit", "test_sk_never_shown");
    }
}
