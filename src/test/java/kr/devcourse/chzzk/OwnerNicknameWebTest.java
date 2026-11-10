package kr.devcourse.chzzk;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** 시청 화면과 방송 목록이 라이브·녹화방송의 소유자 닉네임(없으면 "소유자 없음")을 보여 주는지 HTTP 경계에서 검증한다. */
@SpringBootTest
class OwnerNicknameWebTest {

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

    @BeforeEach
    void setUp() {
        mvc = TestWeb.mvc(context);
    }

    @Test
    void 라이브_시청_화면에_소유자_닉네임이_보인다() throws Exception {
        String json = mvc.perform(post("/live").session(MemberSessions.signUpAs(mvc, "라이브주인공"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"닉네임 라이브\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = json.replaceAll(".*\"id\":(\\d+).*", "$1");

        mvc.perform(get("/live/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("소유자 <span>라이브주인공</span>")));
    }

    @Test
    void 방송_목록에_라이브와_녹화방송의_소유자_닉네임이_보인다() throws Exception {
        mvc.perform(post("/live").session(MemberSessions.signUpAs(mvc, "목록라이브주인"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"목록 라이브\"}"));
        mvc.perform(post("/broadcasts").session(MemberSessions.signUpAs(mvc, "목록녹화주인")).param("title", "목록 녹화")
                .param("videoUrl", "https://example.com/v.mp4"));

        mvc.perform(get("/broadcasts"))
                .andExpect(content().string(containsString("목록라이브주인")))
                .andExpect(content().string(containsString("목록녹화주인")));
    }

    @Test
    void 소유자_없는_라이브와_녹화방송은_소유자_없음으로_보인다() throws Exception {
        jdbc.update("insert into live_broadcast (title, status, started_at) values ('옛 라이브', 'ENDED', now())");
        jdbc.update("insert into recorded_broadcast (title, video_source, video_location, registered_at) "
                + "values ('옛 녹화', 'EXTERNAL', 'https://example.com/old.mp4', now())");
        Long liveId = jdbc.queryForObject("select max(id) from live_broadcast", Long.class);
        Long recordedId = jdbc.queryForObject("select max(id) from recorded_broadcast", Long.class);

        mvc.perform(get("/live/" + liveId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("소유자 없음")));
        mvc.perform(get("/broadcasts/" + recordedId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("소유자 없음")));
        mvc.perform(get("/broadcasts"))
                .andExpect(content().string(containsString("소유자 없음")));
    }
}
