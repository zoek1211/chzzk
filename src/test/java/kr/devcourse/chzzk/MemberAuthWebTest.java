package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class MemberAuthWebTest {

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

    static String newLoginId() {
        return "user" + SEQ.incrementAndGet() + "x";
    }

    MockHttpServletRequestBuilder signUp(String loginId, String password, String nickname) {
        return post("/signup").param("loginId", loginId).param("password", password).param("nickname", nickname);
    }

    MockHttpServletRequestBuilder login(String loginId, String password) {
        return post("/login").param("username", loginId).param("password", password);
    }

    MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void 가입하면_바로_로그인되어_헤더에_닉네임과_로그아웃이_보인다() throws Exception {
        MvcResult result = mvc.perform(signUp(newLoginId(), "password1", "치지직러"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        mvc.perform(get("/broadcasts").session(sessionOf(result)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("치지직러")))
                .andExpect(content().string(containsString("로그아웃")))
                .andExpect(content().string(not(containsString("회원가입"))));
    }

    @Test
    void 비로그인_헤더에는_로그인과_회원가입이_보인다() throws Exception {
        mvc.perform(get("/broadcasts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("로그인")))
                .andExpect(content().string(containsString("회원가입")))
                .andExpect(content().string(not(containsString("로그아웃"))));
    }

    @Test
    void 가입_화면과_로그인_화면은_누구나_열린다() throws Exception {
        mvc.perform(get("/signup")).andExpect(status().isOk());
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void 이미_쓰는_아이디로_가입하면_폼에_오류를_보이고_거절한다() throws Exception {
        String id = newLoginId();
        mvc.perform(signUp(id, "password1", "먼저가입")).andExpect(status().is3xxRedirection());

        mvc.perform(signUp(id, "password2", "나중가입"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("이미 사용 중인 아이디")));
    }

    @Test
    void 입력_규칙을_어기면_가입하지_않고_폼에_이유를_보인다() throws Exception {
        String[][] bad = {
                {"abc", "password1", "닉네임", "아이디는"},
                {"UPPER1234", "password1", "닉네임", "아이디는"},
                {"a".repeat(21), "password1", "닉네임", "아이디는"},
                {"한글아이디", "password1", "닉네임", "아이디는"},
                {"valid1234", "short", "닉네임", "비밀번호는"},
                {"valid1234", "password1", "가", "닉네임은"},
                {"valid1234", "password1", "가".repeat(21), "닉네임은"},
        };
        for (String[] b : bad) {
            mvc.perform(signUp(b[0], b[1], b[2]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString(b[3])))
                    .andExpect(content().string(containsString("name=\"nickname\"")));
        }
        // 위반으로 거절된 아이디는 가입되지 않았으므로 그 아이디로 로그인할 수 없다.
        mvc.perform(login("valid1234", "password1")).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void 가입한_아이디와_비밀번호로_로그인하면_로그인_상태가_된다() throws Exception {
        String id = newLoginId();
        mvc.perform(signUp(id, "password1", "다시온사람"));

        MvcResult result = mvc.perform(login(id, "password1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        mvc.perform(get("/broadcasts").session(sessionOf(result)))
                .andExpect(content().string(containsString("다시온사람")));
    }

    @Test
    void 틀린_아이디와_틀린_비밀번호는_같은_오류로_거절된다() throws Exception {
        String id = newLoginId();
        mvc.perform(signUp(id, "password1", "누구세요"));

        String wrongPassword = mvc.perform(login(id, "wrong-password")).andReturn().getResponse().getRedirectedUrl();
        String wrongId = mvc.perform(login("nobody9999", "password1")).andReturn().getResponse().getRedirectedUrl();
        assertThat(wrongPassword).isEqualTo("/login?error").isEqualTo(wrongId);

        mvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("아이디 또는 비밀번호가 올바르지 않습니다")));
    }

    @Test
    void 로그아웃하면_비로그인_상태가_된다() throws Exception {
        MvcResult signedUp = mvc.perform(signUp(newLoginId(), "password1", "곧떠날사람")).andReturn();
        MockHttpSession session = sessionOf(signedUp);

        mvc.perform(post("/logout").session(session))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/broadcasts").session(session))
                .andExpect(content().string(not(containsString("곧떠날사람"))))
                .andExpect(content().string(containsString("회원가입")));
    }

    @Test
    void 규칙을_어긴_가입_폼은_입력한_비밀번호를_되돌려_보내지_않는다() throws Exception {
        mvc.perform(signUp("ab", "plain-secret-1", "해시"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("plain-secret-1"))));
    }

    @Test
    void CSRF_토큰이_없는_상태_변경_요청은_거절된다() throws Exception {
        MockMvc strict = TestWeb.mvcWithoutCsrf(context);

        strict.perform(signUp(newLoginId(), "password1", "토큰없음")).andExpect(status().isForbidden());
        strict.perform(login("anyone1234", "password1")).andExpect(status().isForbidden());
        strict.perform(post("/live").contentType("application/json").content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        strict.perform(post("/broadcasts").param("title", "t").param("videoUrl", "https://a.example/v.mp4"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 기존_화면의_폼과_fetch는_CSRF_토큰을_보낸다() throws Exception {
        MockMvc strict = TestWeb.mvcWithoutCsrf(context);

        String form = strict.perform(get("/broadcasts/new").session(loggedInWithoutCsrfHelper(strict)))
                .andReturn().getResponse().getContentAsString();
        assertThat(form).contains("name=\"_csrf\"");

        String live = strict.perform(get("/live/new")).andReturn().getResponse().getContentAsString();
        assertThat(live).contains("name=\"_csrf\"", "X-XSRF-TOKEN");
    }

    /** csrf() 후처리기는 공유 필터 체인의 저장소를 바꿔 버리므로, 엄격한 MockMvc로는 쿠키+필드 토큰을 직접 실어 가입한다. */
    private static MockHttpSession loggedInWithoutCsrfHelper(MockMvc strict) throws Exception {
        return (MockHttpSession) strict.perform(post("/signup")
                        .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", "t"))
                        .param("_csrf", "t")
                        .param("loginId", newLoginId()).param("password", "password1").param("nickname", "폼확인"))
                .andReturn().getRequest().getSession(false);
    }
}
