package kr.devcourse.chzzk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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

/** 방송별 댓글 목록 JSON(공개)의 계약을 HTTP 경계에서 검증한다. */
@SpringBootTest
class CommentJsonWebTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = TestWeb.mvc(context);
    }

    MockHttpSession signUp(String nickname) throws Exception {
        return (MockHttpSession) mvc.perform(post("/signup")
                        .param("loginId", "jsonw" + SEQ.incrementAndGet() + "x")
                        .param("password", "password1")
                        .param("nickname", nickname))
                .andReturn().getRequest().getSession(false);
    }

    String liveBroadcast() throws Exception {
        String json = mvc.perform(post("/live").session(TestLogin.member(mvc)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"라이브\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"id\":(\\d+).*", "$1");
    }

    String recordedBroadcast() throws Exception {
        String url = mvc.perform(post("/broadcasts").session(MemberSessions.signUpAs(mvc, "방송주인")).param("title", "녹화")
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    @Test
    void 라이브_댓글_목록을_비로그인도_JSON으로_오래된_순으로_받는다() throws Exception {
        String id = liveBroadcast();
        MockHttpSession session = signUp("민수");
        mvc.perform(post("/live/" + id + "/comments").session(session).param("body", "<b>첫째</b>"));
        mvc.perform(post("/live/" + id + "/comments").session(session).param("body", "둘째"));

        mvc.perform(get("/live/" + id + "/comments").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].authorNickname").value("민수"))
                .andExpect(jsonPath("$[0].body").value("<b>첫째</b>"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].edited").value(false))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[1].body").value("둘째"));
    }

    @Test
    void 녹화방송_댓글_목록도_JSON으로_받고_다른_방송_댓글은_섞이지_않는다() throws Exception {
        String id = recordedBroadcast();
        String liveId = liveBroadcast();
        MockHttpSession session = signUp("영희");
        mvc.perform(post("/broadcasts/" + id + "/comments").session(session).param("body", "녹화 댓글"));
        mvc.perform(post("/live/" + liveId + "/comments").session(session).param("body", "라이브 댓글"));

        mvc.perform(get("/broadcasts/" + id + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].body").value("녹화 댓글"));
    }

    @Test
    void 댓글이_없으면_빈_배열() throws Exception {
        mvc.perform(get("/live/" + liveBroadcast() + "/comments"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void 없는_방송의_목록_요청은_404() throws Exception {
        mvc.perform(get("/live/999999/comments")).andExpect(status().isNotFound());
        mvc.perform(get("/broadcasts/999999/comments")).andExpect(status().isNotFound());
    }

    @Test
    void 라이브_시청_화면은_5초_주기_갱신_코드를_담고_녹화_화면은_담지_않는다() throws Exception {
        String id = liveBroadcast();
        mvc.perform(get("/live/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("setInterval(poll, 5000)")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("textContent")));
        mvc.perform(get("/broadcasts/" + recordedBroadcast()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("setInterval(poll"))));
    }
}
