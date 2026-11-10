package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/** 라이브 소유자 규칙의 HTTP 경계: 비로그인 / 소유자 / 다른 회원이 같은 요청을 보낸다. */
@SpringBootTest
class LiveOwnerWebTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    MockHttpSession owner;
    MockHttpSession other;

    @BeforeEach
    void setUp() throws Exception {
        mvc = TestWeb.mvc(context);
        owner = TestLogin.member(mvc);
        other = TestLogin.member(mvc);
    }

    static MockHttpServletRequestBuilder startLive(String title) {
        return post("/live").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}");
    }

    String start(MockHttpSession as, String title) throws Exception {
        String body = mvc.perform(startLive(title).session(as)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return String.valueOf((Integer) JsonPath.read(body, "$.id"));
    }

    boolean isEnded(String id) throws Exception {
        return LivePages.showsEnded(mvc.perform(get("/live/" + id)).andReturn().getResponse().getContentAsString());
    }

    // --- 송출 화면과 시작 ---

    @Test
    void 비로그인이_송출_화면을_열면_로그인_화면으로_보내진다() throws Exception {
        mvc.perform(get("/live/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
    }

    @Test
    void 로그인한_회원은_송출_화면을_열_수_있다() throws Exception {
        mvc.perform(get("/live/new").session(owner)).andExpect(status().isOk());
    }

    @Test
    void 비로그인이_라이브를_시작하면_401이다() throws Exception {
        mvc.perform(startLive("익명 라이브")).andExpect(status().isUnauthorized());
    }

    @Test
    void 회원이_시작하면_라이브가_만들어지고_응답에_송출_키가_없다() throws Exception {
        mvc.perform(startLive("내 라이브").session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.broadcastKey").doesNotExist());
    }

    @Test
    void 이미_방송_중인_라이브가_있는_회원의_시작은_409이고_다른_회원은_시작할_수_있다() throws Exception {
        start(owner, "첫 라이브");

        mvc.perform(startLive("두 번째").session(owner)).andExpect(status().isConflict());
        mvc.perform(startLive("다른 회원의 라이브").session(other)).andExpect(status().isOk());
    }

    @Test
    void 라이브를_종료하면_같은_회원이_다시_시작할_수_있다() throws Exception {
        String id = start(owner, "끝낼 라이브");
        mvc.perform(post("/live/" + id + "/end").session(owner)).andExpect(status().isOk());

        mvc.perform(startLive("다시 시작").session(owner)).andExpect(status().isOk());
    }

    // --- 종료 ---

    @Test
    void 소유자는_종료할_수_있고_이미_종료된_라이브를_다시_종료해도_성공한다() throws Exception {
        String id = start(owner, "종료 대상");

        mvc.perform(post("/live/" + id + "/end").session(owner)).andExpect(status().isOk());
        mvc.perform(post("/live/" + id + "/end").session(owner)).andExpect(status().isOk());
        assertThat(isEnded(id)).isTrue();
    }

    @Test
    void 다른_회원이_종료하면_403이고_방송은_그대로다() throws Exception {
        String id = start(owner, "지켜질 라이브");

        mvc.perform(post("/live/" + id + "/end").session(other)).andExpect(status().isForbidden());

        assertThat(isEnded(id)).isFalse();
    }

    @Test
    void 비로그인이_종료하면_401이고_방송은_그대로다() throws Exception {
        String id = start(owner, "익명이 못 끄는 라이브");

        mvc.perform(post("/live/" + id + "/end")).andExpect(status().isUnauthorized());

        assertThat(isEnded(id)).isFalse();
    }

    @Test
    void 없는_라이브를_종료하면_404다() throws Exception {
        mvc.perform(post("/live/999999/end").session(owner)).andExpect(status().isNotFound());
    }

    // --- 삭제 ---

    @Test
    void 소유자는_종료된_라이브를_삭제할_수_있고_삭제하면_시청_화면이_404다() throws Exception {
        String id = start(owner, "지울 라이브");
        mvc.perform(post("/live/" + id + "/end").session(owner)).andExpect(status().isOk());

        mvc.perform(post("/live/" + id + "/delete").session(owner)).andExpect(status().is3xxRedirection());

        mvc.perform(get("/live/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void 방송_중인_라이브의_삭제는_409이고_라이브는_남는다() throws Exception {
        String id = start(owner, "방송 중 삭제 시도");

        mvc.perform(post("/live/" + id + "/delete").session(owner)).andExpect(status().isConflict());

        mvc.perform(get("/live/" + id)).andExpect(status().isOk());
    }

    @Test
    void 다른_회원이_삭제하면_403이고_비로그인은_401이며_라이브는_남는다() throws Exception {
        String id = start(owner, "남의 라이브");
        mvc.perform(post("/live/" + id + "/end").session(owner)).andExpect(status().isOk());

        mvc.perform(post("/live/" + id + "/delete").session(other)).andExpect(status().isForbidden());
        mvc.perform(post("/live/" + id + "/delete")).andExpect(status().isUnauthorized());

        mvc.perform(get("/live/" + id)).andExpect(status().isOk());
    }

    @Test
    void 방송_중인_남의_라이브를_삭제하려_하면_409가_아니라_403이다() throws Exception {
        String id = start(owner, "남의 방송 중");

        mvc.perform(post("/live/" + id + "/delete").session(other)).andExpect(status().isForbidden());
    }

    @Test
    void 없는_라이브를_삭제하면_404다() throws Exception {
        mvc.perform(post("/live/999999/delete").session(owner)).andExpect(status().isNotFound());
    }

    // --- 소유자 없는 기존 라이브 ---

    @Test
    void 소유자_없는_기존_라이브는_누구도_종료하거나_삭제할_수_없고_시청은_된다() throws Exception {
        jdbc.update("insert into live_broadcast (title, status, started_at, ended_at) "
                + "values ('옛날 라이브', 'ENDED', current_timestamp, current_timestamp)");
        String id = String.valueOf(jdbc.queryForObject("select max(id) from live_broadcast", Long.class));
        jdbc.update("insert into live_broadcast (title, status, started_at) "
                + "values ('옛날 방송 중', 'ON_AIR', current_timestamp)");
        String onAirId = String.valueOf(jdbc.queryForObject("select max(id) from live_broadcast", Long.class));

        mvc.perform(post("/live/" + id + "/end").session(owner)).andExpect(status().isForbidden());
        mvc.perform(post("/live/" + id + "/delete").session(owner)).andExpect(status().isForbidden());
        mvc.perform(post("/live/" + onAirId + "/end").session(owner)).andExpect(status().isForbidden());
        mvc.perform(get("/live/" + id)).andExpect(status().isOk());
    }
}
