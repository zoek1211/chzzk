package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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

/** 방송별 댓글 작성과 목록을 HTTP 경계에서 검증한다. */
@SpringBootTest
class CommentWebTest {

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

    /** 새 회원으로 가입해 로그인된 세션을 돌려준다. */
    MockHttpSession signUp(String nickname) throws Exception {
        return (MockHttpSession) mvc.perform(post("/signup")
                        .param("loginId", "writer" + SEQ.incrementAndGet() + "x")
                        .param("password", "password1")
                        .param("nickname", nickname))
                .andReturn().getRequest().getSession(false);
    }

    String recordedBroadcast(String title) throws Exception {
        String url = mvc.perform(post("/broadcasts").session(MemberSessions.signUpAs(mvc, "방송주인")).param("title", title)
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    String liveBroadcast(String title) throws Exception {
        String json = mvc.perform(post("/live").session(TestLogin.member(mvc)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"id\":(\\d+).*", "$1");
    }

    void comment(String path, MockHttpSession session, String body) throws Exception {
        mvc.perform(post(path + "/comments").session(session).param("body", body));
    }

    String page(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 회원이_녹화방송에_쓴_댓글이_닉네임과_함께_비로그인에게도_보인다() throws Exception {
        String path = "/broadcasts/" + recordedBroadcast("댓글 받을 녹화방송");
        MockHttpSession session = signUp("댓글러");

        mvc.perform(post(path + "/comments").session(session).param("body", "첫 댓글입니다"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("첫 댓글입니다")))
                .andExpect(content().string(containsString("댓글러")));
    }

    @Test
    void 회원이_라이브_방송에도_댓글을_쓸_수_있다() throws Exception {
        String path = "/live/" + liveBroadcast("댓글 받을 라이브");
        MockHttpSession session = signUp("라이브러");

        mvc.perform(post(path + "/comments").session(session).param("body", "라이브 댓글"))
                .andExpect(status().is3xxRedirection());

        assertThat(page(path)).contains("라이브 댓글", "라이브러");
    }

    @Test
    void 비로그인_댓글_작성은_거절되고_저장되지_않는다() throws Exception {
        String path = "/broadcasts/" + recordedBroadcast("비로그인 시도");

        mvc.perform(post(path + "/comments").param("body", "몰래 쓴 댓글"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        assertThat(page(path)).doesNotContain("몰래 쓴 댓글");
    }

    @Test
    void 비로그인_화면에는_작성_폼_대신_로그인_안내가_보이고_회원에게는_작성_폼이_보인다() throws Exception {
        String path = "/broadcasts/" + recordedBroadcast("폼 확인");

        assertThat(page(path)).contains("로그인하면 댓글을 쓸 수 있습니다").doesNotContain("name=\"body\"");

        String html = mvc.perform(get(path).session(signUp("폼러")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("name=\"body\"").doesNotContain("로그인하면 댓글을 쓸 수 있습니다");
    }

    @Test
    void 빈_댓글_공백만_있는_댓글_501자_댓글은_400으로_거절되고_500자는_허용된다() throws Exception {
        String path = "/broadcasts/" + recordedBroadcast("검증");
        MockHttpSession session = signUp("검증러");

        for (String bad : new String[] {"", "   \n ", "가".repeat(501)}) {
            mvc.perform(post(path + "/comments").session(session).param("body", bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(path + "/comments").session(session).param("body", "나".repeat(500)))
                .andExpect(status().is3xxRedirection());

        assertThat(page(path)).contains("나".repeat(500)).doesNotContain("가".repeat(501));
    }

    @Test
    void 없는_방송에_댓글을_쓰면_404다() throws Exception {
        MockHttpSession session = signUp("없는방송러");

        mvc.perform(post("/broadcasts/999999/comments").session(session).param("body", "x"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/live/999999/comments").session(session).param("body", "x"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 목록은_방송별로_분리되고_오래된_순으로_나온다() throws Exception {
        String a = "/broadcasts/" + recordedBroadcast("방송 A");
        String b = "/broadcasts/" + recordedBroadcast("방송 B");
        String live = "/live/" + liveBroadcast("방송 L");
        MockHttpSession session = signUp("순서러");

        comment(a, session, "A-첫째");
        comment(a, session, "A-둘째");
        comment(b, session, "B-유일");
        comment(live, session, "L-유일");

        String htmlA = page(a);
        assertThat(htmlA.indexOf("A-첫째")).isPositive().isLessThan(htmlA.indexOf("A-둘째"));
        assertThat(htmlA).doesNotContain("B-유일", "L-유일");
        assertThat(page(b)).contains("B-유일").doesNotContain("A-첫째", "L-유일");
        assertThat(page(live)).contains("L-유일").doesNotContain("A-첫째", "B-유일");
    }

    @Test
    void 댓글_본문의_HTML은_실행되지_않고_글자로_이스케이프되어_보인다() throws Exception {
        String path = "/broadcasts/" + recordedBroadcast("이스케이프");
        MockHttpSession session = signUp("해커");

        comment(path, session, "<script>alert(1)</script>");

        assertThat(page(path))
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script>alert(1)</script>");
    }
}
