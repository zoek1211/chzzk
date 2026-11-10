package kr.devcourse.chzzk;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** 같은 데이터 디렉터리로 다시 띄워도 댓글이 남아 있는지. */
class CommentRestartPersistenceTest {

    @TempDir
    Path dataDir;

    @Test
    void 재시작한_뒤에도_댓글과_작성자_닉네임이_남아_있다() throws Exception {
        String detailUrl;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = TestWeb.mvc((WebApplicationContext) first);
            detailUrl = mvc.perform(post("/broadcasts").session(MemberSessions.signUpAs(mvc, "방송주인")).param("title", "재시작 댓글 방송")
                            .param("videoUrl", "https://example.com/v.mp4"))
                    .andReturn().getResponse().getRedirectedUrl();
            MockHttpSession session = (MockHttpSession) mvc.perform(post("/signup")
                            .param("loginId", "persist1").param("password", "password1").param("nickname", "남는사람"))
                    .andReturn().getRequest().getSession(false);
            mvc.perform(post(detailUrl + "/comments").session(session).param("body", "재시작해도 남는 댓글"))
                    .andExpect(status().is3xxRedirection());
        }

        try (ConfigurableApplicationContext second = start()) {
            TestWeb.mvc((WebApplicationContext) second).perform(get(detailUrl))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("재시작해도 남는 댓글")))
                    .andExpect(content().string(containsString("남는사람")));
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(ChzzkApplication.class)
                .run("--chzzk.data-dir=" + dataDir, "--server.port=0");
    }
}
