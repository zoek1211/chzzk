package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
class LiveEndWebTest {

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

    /** 새 회원이 라이브를 시작한다(회원당 방송 중인 라이브는 하나라서 라이브마다 새 회원). */
    record Live(String id, MockHttpSession owner) {
    }

    Live start(String title) throws Exception {
        MockHttpSession owner = TestLogin.member(mvc);
        String body = mvc.perform(post("/live").session(owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return new Live(String.valueOf((Integer) JsonPath.read(body, "$.id")), owner);
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getContentAsString();
    }

    int end(Live live) throws Exception {
        return mvc.perform(post("/live/" + live.id() + "/end").session(live.owner())).andReturn().getResponse().getStatus();
    }

    @Test
    void 소유자가_종료하면_시청_화면이_종료_안내와_시작_종료_시각을_보여준다() throws Exception {
        Live live = start("곧 끝날 라이브");
        assertThat(LivePages.showsEnded(page("/live/" + live.id()))).isFalse();

        assertThat(end(live)).isEqualTo(200);

        String html = page("/live/" + live.id());
        assertThat(LivePages.showsEnded(html)).isTrue();
        assertThat(html).contains("방송이 종료되었습니다", "곧 끝날 라이브", "종료");
        assertThat(html).doesNotContain("<video");
        assertThat(html.split("<time", -1)).hasSize(3); // 시작 시각과 종료 시각
    }

    @Test
    void 이미_종료된_라이브를_다시_종료해도_성공하고_종료_시각은_바뀌지_않는다() throws Exception {
        Live live = start("두 번 끝내는 라이브");
        assertThat(end(live)).isEqualTo(200);
        String before = TestWeb.withoutCsrfMeta(page("/live/" + live.id()));
        Thread.sleep(1100);

        assertThat(end(live)).isEqualTo(200);

        assertThat(TestWeb.withoutCsrfMeta(page("/live/" + live.id()))).isEqualTo(before);
    }

    @Test
    void 방송_목록은_방송_중을_먼저_종료를_뒤에_각각_최신순으로_보여주고_종료에는_종료_배지가_붙는다() throws Exception {
        Live endedOld = start("정렬-종료-오래됨");
        Live onAirOld = start("정렬-방송중-오래됨");
        Live endedNew = start("정렬-종료-최신");
        Live onAirNew = start("정렬-방송중-최신");
        end(endedOld);
        end(endedNew);

        String html = page("/broadcasts");

        int onNew = html.indexOf("정렬-방송중-최신");
        int onOld = html.indexOf("정렬-방송중-오래됨");
        int endNew = html.indexOf("정렬-종료-최신");
        int endOld = html.indexOf("정렬-종료-오래됨");
        assertThat(onNew).isPositive().isLessThan(onOld);
        assertThat(onOld).isLessThan(endNew);
        assertThat(endNew).isLessThan(endOld);
        assertThat(html).contains("badge-ended");
        assertThat(html).contains("badge-live");
    }

    @Test
    void 방송_중_시청_화면은_종료_안내를_숨겨_둔_채_두고_종료를_받으면_새로고침_없이_바꾼다() throws Exception {
        Live live = start("제자리 전환");

        String html = page("/live/" + live.id());

        assertThat(html).contains("id=\"ended-view\"", "'ended'");
        assertThat(LivePages.showsEnded(html)).isFalse();
        assertThat(html).doesNotContain("location.reload");
    }

    @Test
    void 방송_중_시청_화면은_연결이_끊기면_끊김_안내와_새로고침_안내를_보여_준다() throws Exception {
        Live live = start("연결 끊김");

        String html = page("/live/" + live.id());

        assertThat(html).contains("ws.onclose", "ws.onerror", "연결이 끊어졌습니다", "새로고침");
    }

    @Test
    void 송출_화면은_송출_키를_쓰지_않고_로그인_세션으로_접속한다() throws Exception {
        String html = mvc.perform(get("/live/new").session(TestLogin.member(mvc)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("type: 'auth'", "X-Broadcast-Key", "broadcastKey", "?key=");
    }

    @Test
    void 송출_화면의_방송_종료_버튼은_종료를_요청하고_연결을_닫는다() throws Exception {
        String html = mvc.perform(get("/live/new").session(TestLogin.member(mvc)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("방송 종료", "/end", "closeBroadcast()");
    }
}
