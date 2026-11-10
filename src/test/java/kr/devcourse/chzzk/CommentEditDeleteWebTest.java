package kr.devcourse.chzzk;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
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

/** 댓글 수정·삭제(작성자만)와 방송 삭제 시 댓글 정리를 HTTP 경계에서 검증한다. */
@SpringBootTest
class CommentEditDeleteWebTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;
    MockHttpSession owner;   // 방송 소유자(댓글 작성자 아님)
    MockHttpSession author;  // 댓글 작성자
    MockHttpSession other;   // 다른 회원
    String broadcastId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestWeb.mvc(context);
        owner = MemberSessions.signUpAs(mvc, "소유자");
        author = MemberSessions.signUpAs(mvc, "작성자");
        other = MemberSessions.signUpAs(mvc, "다른회원");
        String url = mvc.perform(post("/broadcasts").session(owner).param("title", "녹화")
                        .param("videoUrl", "https://example.com/v.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        broadcastId = url.substring(url.lastIndexOf('/') + 1);
    }

    String comment(String path, MockHttpSession session, String body) throws Exception {
        mvc.perform(post(path + "/comments").session(session).param("body", body));
        String json = mvc.perform(get(path + "/comments")).andReturn().getResponse().getContentAsString();
        java.util.List<Integer> ids = JsonPath.read(json, "$[?(@.body == '" + body + "')].id");
        return String.valueOf(ids.get(0));
    }

    String recordedPath() {
        return "/broadcasts/" + broadcastId;
    }

    @Test
    void 작성자는_댓글을_수정할_수_있고_수정됨_표시가_JSON과_화면에_나타난다() throws Exception {
        String id = comment(recordedPath(), author, "원래 글");

        mvc.perform(post("/comments/" + id + "/edit").session(author).param("body", "고친 글"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(recordedPath() + "#comments"));

        mvc.perform(get(recordedPath() + "/comments"))
                .andExpect(jsonPath("$[0].body").value("고친 글"))
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
        mvc.perform(get(recordedPath()))
                .andExpect(content().string(containsString("고친 글")))
                .andExpect(content().string(matchesPattern("(?s).*\\(수정됨 · \\d{2}:\\d{2}\\).*")));
    }

    @Test
    void 수정하지_않은_댓글은_updatedAt이_비고_수정됨이_보이지_않는다() throws Exception {
        comment(recordedPath(), author, "그대로");
        mvc.perform(get(recordedPath() + "/comments")).andExpect(jsonPath("$[0].updatedAt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(recordedPath()))
                .andExpect(content().string(not(containsString("(수정됨"))));
    }

    @Test
    void 수정에도_1에서_500자_규칙이_적용된다() throws Exception {
        String id = comment(recordedPath(), author, "원래 글");

        mvc.perform(post("/comments/" + id + "/edit").session(author).param("body", "   "))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/comments/" + id + "/edit").session(author).param("body", "가".repeat(501)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/comments/" + id + "/edit").session(author).param("body", "가".repeat(500)))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get(recordedPath() + "/comments")).andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
    }

    @Test
    void 다른_회원과_방송_소유자는_수정할_수_없다_403() throws Exception {
        String id = comment(recordedPath(), author, "원래 글");

        mvc.perform(post("/comments/" + id + "/edit").session(other).param("body", "해킹"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/comments/" + id + "/edit").session(owner).param("body", "해킹"))
                .andExpect(status().isForbidden());
        mvc.perform(get(recordedPath() + "/comments"))
                .andExpect(jsonPath("$[0].body").value("원래 글"))
                .andExpect(jsonPath("$[0].updatedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 비로그인은_수정과_삭제를_거절당한다() throws Exception {
        String id = comment(recordedPath(), author, "원래 글");

        mvc.perform(post("/comments/" + id + "/edit").param("body", "해킹"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
        mvc.perform(post("/comments/" + id + "/delete"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
        mvc.perform(get(recordedPath() + "/comments")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 작성자는_댓글을_삭제할_수_있다() throws Exception {
        String id = comment(recordedPath(), author, "지울 글");

        mvc.perform(post("/comments/" + id + "/delete").session(author))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(recordedPath() + "#comments"));

        mvc.perform(get(recordedPath() + "/comments")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 다른_회원과_방송_소유자는_삭제할_수_없다_403() throws Exception {
        String id = comment(recordedPath(), author, "지킬 글");

        mvc.perform(post("/comments/" + id + "/delete").session(other)).andExpect(status().isForbidden());
        mvc.perform(post("/comments/" + id + "/delete").session(owner)).andExpect(status().isForbidden());
        mvc.perform(get(recordedPath() + "/comments")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 없는_댓글의_수정과_삭제는_404() throws Exception {
        mvc.perform(post("/comments/999999/edit").session(author).param("body", "x")).andExpect(status().isNotFound());
        mvc.perform(post("/comments/999999/delete").session(author)).andExpect(status().isNotFound());
    }

    @Test
    void 라이브_댓글도_작성자만_수정_삭제하고_라이브_화면으로_돌아간다() throws Exception {
        String liveId = mvc.perform(post("/live").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"라이브\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1");
        String path = "/live/" + liveId;
        String id = comment(path, author, "라이브 글");

        mvc.perform(post("/comments/" + id + "/edit").session(owner).param("body", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/comments/" + id + "/edit").session(author).param("body", "고침"))
                .andExpect(redirectedUrl(path + "#comments"));
        mvc.perform(post("/comments/" + id + "/delete").session(owner)).andExpect(status().isForbidden());
        mvc.perform(post("/comments/" + id + "/delete").session(author))
                .andExpect(redirectedUrl(path + "#comments"));
        mvc.perform(get(path + "/comments")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 수정_삭제_버튼은_작성자에게만_보인다() throws Exception {
        String id = comment(recordedPath(), author, "버튼 글");
        String editAction = "/comments/" + id + "/edit";

        mvc.perform(get(recordedPath()).session(author)).andExpect(content().string(containsString(editAction)));
        mvc.perform(get(recordedPath()).session(owner)).andExpect(content().string(not(containsString(editAction))));
        mvc.perform(get(recordedPath()).session(other)).andExpect(content().string(not(containsString(editAction))));
        mvc.perform(get(recordedPath())).andExpect(content().string(not(containsString(editAction))));
    }

    @Test
    void 댓글_JSON은_회원_id를_싣지_않고_요청한_회원의_mine만_알려_준다() throws Exception {
        comment(recordedPath(), author, "내 글");

        mvc.perform(get(recordedPath() + "/comments").session(author))
                .andExpect(jsonPath("$[0].mine").value(true))
                .andExpect(jsonPath("$[0].authorId").doesNotExist());
        mvc.perform(get(recordedPath() + "/comments").session(other))
                .andExpect(jsonPath("$[0].mine").value(false));
        mvc.perform(get(recordedPath() + "/comments"))
                .andExpect(jsonPath("$[0].mine").value(false));
        mvc.perform(get(recordedPath()).session(author))
                .andExpect(content().string(not(containsString("data-member-id"))));
    }

    @Test
    void 라이브_폴링_렌더러는_작성자_버튼과_수정됨을_textContent로_그린다() throws Exception {
        String liveId = mvc.perform(post("/live").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"라이브\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1");
        mvc.perform(get("/live/" + liveId).session(author))
                .andExpect(content().string(not(containsString("data-member-id"))))
                .andExpect(content().string(containsString("c.mine")))
                .andExpect(content().string(containsString("/delete")))
                .andExpect(content().string(not(containsString("innerHTML"))));
    }

    @Test
    void 녹화방송을_삭제하면_그_방송_댓글만_사라진다() throws Exception {
        String otherUrl = mvc.perform(post("/broadcasts").session(owner).param("title", "다른 녹화")
                        .param("videoUrl", "https://example.com/w.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        String otherId = otherUrl.substring(otherUrl.lastIndexOf('/') + 1);
        comment(recordedPath(), author, "사라질 글");
        comment("/broadcasts/" + otherId, author, "남을 글");

        mvc.perform(post(recordedPath() + "/delete").session(owner)).andExpect(status().is3xxRedirection());

        mvc.perform(get("/broadcasts/" + otherId + "/comments"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].body").value("남을 글"));
    }

    @Test
    void 라이브를_삭제하면_그_라이브_댓글만_사라진다() throws Exception {
        String liveId = startLive(owner, "삭제할 라이브");
        String keepLiveId = startLive(MemberSessions.signUpAs(mvc, "다른방송자"), "남는 라이브");
        comment("/live/" + liveId, author, "사라질 글");
        comment("/live/" + keepLiveId, author, "남을 글");
        comment(recordedPath(), author, "녹화 글");
        mvc.perform(post("/live/" + liveId + "/end").session(owner)).andExpect(status().isOk());

        mvc.perform(post("/live/" + liveId + "/delete").session(owner)).andExpect(status().is3xxRedirection());

        mvc.perform(get("/live/" + keepLiveId + "/comments")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(recordedPath() + "/comments")).andExpect(jsonPath("$.length()").value(1));
    }

    private String startLive(MockHttpSession session, String title) throws Exception {
        return mvc.perform(post("/live").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1");
    }
}
