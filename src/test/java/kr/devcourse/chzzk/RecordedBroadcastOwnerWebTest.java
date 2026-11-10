package kr.devcourse.chzzk;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import kr.devcourse.chzzk.broadcast.RecordedBroadcast;
import kr.devcourse.chzzk.broadcast.RecordedBroadcastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** 녹화방송 소유자와 관리 권한: 비로그인 / 소유자 / 다른 회원 세 사람이 같은 요청을 보낸다. */
@SpringBootTest
class RecordedBroadcastOwnerWebTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @Autowired
    WebApplicationContext context;

    /** 회원 기능 이전의 소유자 없는 방송을 만들어 두는 데만 쓴다(조회에는 쓰지 않는다). */
    @Autowired
    RecordedBroadcastRepository legacySeed;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = TestWeb.mvc(context);
    }

    String register(MockHttpSession session, String title) throws Exception {
        return mvc.perform(multipart("/broadcasts").session(session)
                        .param("title", title)
                        .param("videoUrl", "https://cdn.example.com/vod/x.mp4"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    @Test
    void 비로그인이_등록_화면을_열면_로그인_화면으로_가고_로그인하면_등록_화면으로_돌아온다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/broadcasts/new").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        String loginId = "returner1x";
        mvc.perform(post("/signup").param("loginId", loginId).param("password", "password1")
                .param("nickname", "돌아온이"));
        mvc.perform(post("/login").session(session).param("username", loginId).param("password", "password1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/broadcasts/new?continue"));
    }

    @Test
    void 비로그인의_등록_처리_요청은_로그인_화면으로_보내고_등록하지_않는다() throws Exception {
        mvc.perform(multipart("/broadcasts")
                        .param("title", "익명이 올린 방송")
                        .param("videoUrl", "https://cdn.example.com/vod/anon.mp4"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mvc.perform(get("/broadcasts"))
                .andExpect(content().string(not(containsString("익명이 올린 방송"))));
    }

    @Test
    void 로그인한_회원이_등록하면_상세_화면에_소유자_닉네임이_보이고_비로그인에게도_공개다() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "방송왕");
        String detailUrl = register(owner, "소유자 확인 방송");

        mvc.perform(get(detailUrl))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("소유자 확인 방송")))
                .andExpect(content().string(containsString("방송왕")));
    }

    @Test
    void 수정과_삭제_버튼은_소유자에게만_보인다() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "주인장");
        MockHttpSession other = MemberSessions.signUpAs(mvc, "지나가던이");
        String detailUrl = register(owner, "버튼 확인 방송");

        mvc.perform(get(detailUrl).session(owner))
                .andExpect(content().string(containsString(detailUrl + "/edit")))
                .andExpect(content().string(containsString(detailUrl + "/delete")));
        mvc.perform(get(detailUrl).session(other))
                .andExpect(content().string(not(containsString("/edit"))))
                .andExpect(content().string(not(containsString("/delete"))));
        mvc.perform(get(detailUrl))
                .andExpect(content().string(not(containsString("/edit"))))
                .andExpect(content().string(not(containsString("/delete"))));
    }

    @Test
    void 소유자는_제목을_수정할_수_있다() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "고치는이");
        String detailUrl = register(owner, "오타 있는 제모");

        mvc.perform(get(detailUrl + "/edit").session(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("오타 있는 제모")));
        mvc.perform(post(detailUrl + "/edit").session(owner).param("title", "고친 제목"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(detailUrl));

        mvc.perform(get(detailUrl))
                .andExpect(content().string(containsString("고친 제목")))
                .andExpect(content().string(not(containsString("오타 있는 제모"))));
    }

    @Test
    void 제목_수정에도_등록과_같은_제목_규칙이_적용된다() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "규칙이");
        String detailUrl = register(owner, "멀쩡한 제목");

        mvc.perform(post(detailUrl + "/edit").session(owner).param("title", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("제목을 입력")));
        mvc.perform(get(detailUrl))
                .andExpect(content().string(containsString("멀쩡한 제목")));
    }

    @Test
    void 다른_회원은_수정할_수_없고_비로그인은_로그인_화면으로_간다() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "원주인");
        MockHttpSession other = MemberSessions.signUpAs(mvc, "침입자");
        String detailUrl = register(owner, "지켜야 할 제목");

        mvc.perform(get(detailUrl + "/edit").session(other)).andExpect(status().isForbidden());
        mvc.perform(post(detailUrl + "/edit").session(other).param("title", "해킹됨"))
                .andExpect(status().isForbidden());
        mvc.perform(get(detailUrl + "/edit"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(post(detailUrl + "/edit").param("title", "익명 해킹"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));

        mvc.perform(get(detailUrl))
                .andExpect(content().string(containsString("지켜야 할 제목")))
                .andExpect(content().string(not(containsString("해킹"))));
    }

    @Test
    void 소유자가_삭제하면_상세는_404가_되고_업로드한_영상도_더_이상_전송되지_않는다() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "지우는이");
        String detailUrl = mvc.perform(multipart("/broadcasts").session(owner)
                        .file(new MockMultipartFile("videoFile", "clip.mp4", "video/mp4", new byte[] {1, 2, 3}))
                        .param("title", "지울 방송"))
                .andReturn().getResponse().getRedirectedUrl();
        String html = mvc.perform(get(detailUrl)).andReturn().getResponse().getContentAsString();
        String videoUrl = html.replaceAll("(?s).*<video[^>]*src=\"([^\"]+)\".*", "$1");
        mvc.perform(get(videoUrl)).andExpect(status().isOk());

        mvc.perform(post(detailUrl + "/delete").session(owner))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/broadcasts"));

        mvc.perform(get(detailUrl)).andExpect(status().isNotFound());
        mvc.perform(get(videoUrl)).andExpect(status().isNotFound());
        mvc.perform(get("/broadcasts")).andExpect(content().string(not(containsString("지울 방송"))));
    }

    @Test
    void 다른_회원은_삭제할_수_없고_비로그인은_로그인_화면으로_간다() throws Exception {
        MockHttpSession owner = MemberSessions.signUpAs(mvc, "지켜주는이");
        MockHttpSession other = MemberSessions.signUpAs(mvc, "훼방꾼");
        String detailUrl = register(owner, "남아야 할 방송");

        mvc.perform(post(detailUrl + "/delete").session(other)).andExpect(status().isForbidden());
        mvc.perform(post(detailUrl + "/delete"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));

        mvc.perform(get(detailUrl)).andExpect(status().isOk())
                .andExpect(content().string(containsString("남아야 할 방송")));
    }

    @Test
    void 소유자_없는_기존_녹화방송은_어떤_회원도_수정_삭제할_수_없다() throws Exception {
        String detailUrl = "/broadcasts/" + legacySeed.save(RecordedBroadcast.withExternalVideo(
                "옛날 방송", "https://cdn.example.com/vod/old.mp4")).getId();
        MockHttpSession member = MemberSessions.signUpAs(mvc, "새회원");

        mvc.perform(get(detailUrl)).andExpect(status().isOk())
                .andExpect(content().string(containsString("옛날 방송")));
        mvc.perform(get(detailUrl).session(member))
                .andExpect(content().string(not(containsString("/edit"))));
        mvc.perform(get(detailUrl + "/edit").session(member)).andExpect(status().isForbidden());
        mvc.perform(post(detailUrl + "/edit").session(member).param("title", "바뀜"))
                .andExpect(status().isForbidden());
        mvc.perform(post(detailUrl + "/delete").session(member)).andExpect(status().isForbidden());
        mvc.perform(get(detailUrl)).andExpect(status().isOk());
    }
}
