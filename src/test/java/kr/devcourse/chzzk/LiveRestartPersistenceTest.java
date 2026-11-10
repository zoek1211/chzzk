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

/** 같은 데이터 디렉터리로 애플리케이션을 다시 띄웠을 때 라이브 방송 정보가 남아 있는지. */
class LiveRestartPersistenceTest {

    @TempDir
    Path dataDir;

    @Test
    void 재시작한_뒤에도_라이브_방송의_제목과_시작_시각이_조회되고_소유자도_유지된다() throws Exception {
        String liveId;
        MockHttpSession ownerSession;
        String watchHtmlBefore;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = mvcOf(first);
            ownerSession = TestLogin.member(mvc);
            String body = mvc.perform(post("/live").session(ownerSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"재시작 후에도 남을 라이브\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            liveId = String.valueOf((Integer) JsonPath.read(body, "$.id"));
            watchHtmlBefore = mvc.perform(get("/live/" + liveId)).andReturn().getResponse().getContentAsString();
        }

        try (ConfigurableApplicationContext second = start()) {
            MockMvc mvc = mvcOf(second);
            String watchHtmlAfter = mvc.perform(get("/live/" + liveId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(watchHtmlAfter).contains("재시작 후에도 남을 라이브");
            assertThat(startedAtOf(watchHtmlAfter)).isEqualTo(startedAtOf(watchHtmlBefore));

            String list = mvc.perform(get("/broadcasts")).andReturn().getResponse().getContentAsString();
            assertThat(list).contains("재시작 후에도 남을 라이브");
        }
    }

    @Test
    void 재시작한_뒤에도_같은_회원이_소유자로_인정되고_다른_회원은_거절된다() throws Exception {
        String loginId = TestLogin.newLoginId();
        String liveId;
        String endedId;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = mvcOf(first);
            MockHttpSession session = (MockHttpSession) mvc.perform(post("/signup")
                            .param("loginId", loginId).param("password", "password1").param("nickname", "소유자"))
                    .andReturn().getRequest().getSession(false);
            endedId = startLive(mvc, session, "옛 라이브");
            mvc.perform(post("/live/" + endedId + "/end").session(session)).andExpect(status().isOk());
            liveId = endedId;
        }

        try (ConfigurableApplicationContext second = start()) {
            MockMvc mvc = mvcOf(second);
            MockHttpSession owner = (MockHttpSession) mvc.perform(post("/login")
                            .param("username", loginId).param("password", "password1"))
                    .andReturn().getRequest().getSession(false);
            MockHttpSession other = TestLogin.member(mvc);

            mvc.perform(post("/live/" + liveId + "/delete").session(other)).andExpect(status().isForbidden());
            mvc.perform(post("/live/" + liveId + "/delete").session(owner)).andExpect(status().is3xxRedirection());
        }
    }

    @Test
    void 재시작하면_방송_중이던_라이브_방송은_종료로_보이고_이미_종료된_것의_종료_시각은_그대로다() throws Exception {
        String onAirId;
        String endedId;
        String endedHtmlBefore;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = mvcOf(first);
            onAirId = startLive(mvc, TestLogin.member(mvc), "재시작에 끝날 라이브");
            MockHttpSession endedOwner = TestLogin.member(mvc);
            endedId = startLive(mvc, endedOwner, "미리 끝낸 라이브");
            mvc.perform(post("/live/" + endedId + "/end").session(endedOwner))
                    .andExpect(status().isOk());
            endedHtmlBefore = TestWeb.withoutCsrfMeta(
                    mvc.perform(get("/live/" + endedId)).andReturn().getResponse().getContentAsString());
            assertThat(LivePages.showsEnded(
                    mvc.perform(get("/live/" + onAirId)).andReturn().getResponse().getContentAsString())).isFalse();
        }

        Thread.sleep(1100);

        try (ConfigurableApplicationContext second = start()) {
            MockMvc mvc = mvcOf(second);
            assertThat(LivePages.showsEnded(
                    mvc.perform(get("/live/" + onAirId)).andReturn().getResponse().getContentAsString())).isTrue();
            assertThat(TestWeb.withoutCsrfMeta(
                    mvc.perform(get("/live/" + endedId)).andReturn().getResponse().getContentAsString()))
                    .isEqualTo(endedHtmlBefore);
        }
    }

    private static String startLive(MockMvc mvc, MockHttpSession session, String title) throws Exception {
        String body = mvc.perform(post("/live").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return String.valueOf((Integer) JsonPath.read(body, "$.id"));
    }

    private static String startedAtOf(String html) {
        return html.replaceAll("(?s).*?<time[^>]*>([^<]+)</time>.*", "$1");
    }

    private ConfigurableApplicationContext start() {
        // 커맨드라인 인자로 넘겨야 application.properties 의 기본 데이터 디렉터리보다 우선한다.
        return new SpringApplicationBuilder(ChzzkApplication.class)
                .run("--chzzk.data-dir=" + dataDir, "--server.port=0");
    }

    private static MockMvc mvcOf(ConfigurableApplicationContext context) {
        return TestWeb.mvc((WebApplicationContext) context);
    }
}
