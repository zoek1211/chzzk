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

    record Live(String id, String key) {
    }

    Live start(String title) throws Exception {
        String body = mvc.perform(post("/live").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return new Live(String.valueOf((Integer) JsonPath.read(body, "$.id")), JsonPath.read(body, "$.broadcastKey"));
    }

    String page(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getContentAsString();
    }

    int end(String id, String key) throws Exception {
        var request = post("/live/" + id + "/end");
        if (key != null) {
            request = request.header("X-Broadcast-Key", key);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void 올바른_송출_키로_종료하면_시청_화면이_종료_안내와_시작_종료_시각을_보여준다() throws Exception {
        Live live = start("곧 끝날 라이브");
        assertThat(LivePages.showsEnded(page("/live/" + live.id()))).isFalse();

        assertThat(end(live.id(), live.key())).isEqualTo(200);

        String html = page("/live/" + live.id());
        assertThat(LivePages.showsEnded(html)).isTrue();
        assertThat(html).contains("방송이 종료되었습니다", "곧 끝날 라이브", "종료");
        assertThat(html).doesNotContain("<video");
        assertThat(html.split("<time", -1)).hasSize(3); // 시작 시각과 종료 시각
    }

    @Test
    void 틀린_송출_키나_키_없이_종료하면_403이고_방송_상태는_그대로다() throws Exception {
        Live live = start("지켜질 라이브");

        assertThat(end(live.id(), "wrong-key")).isEqualTo(403);
        assertThat(end(live.id(), null)).isEqualTo(403);

        assertThat(LivePages.showsEnded(page("/live/" + live.id()))).isFalse();
    }

    @Test
    void 다른_라이브_방송의_송출_키로는_종료할_수_없다() throws Exception {
        Live a = start("라이브 A");
        Live b = start("라이브 B");

        assertThat(end(a.id(), b.key())).isEqualTo(403);
    }

    @Test
    void 없는_라이브_방송을_종료하면_404다() throws Exception {
        mvc.perform(post("/live/999999/end").header("X-Broadcast-Key", "any"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 이미_종료된_라이브를_다시_종료해도_성공하고_종료_시각은_바뀌지_않는다() throws Exception {
        Live live = start("두 번 끝내는 라이브");
        assertThat(end(live.id(), live.key())).isEqualTo(200);
        String before = TestWeb.withoutCsrfMeta(page("/live/" + live.id()));
        Thread.sleep(1100);

        assertThat(end(live.id(), live.key())).isEqualTo(200);

        assertThat(TestWeb.withoutCsrfMeta(page("/live/" + live.id()))).isEqualTo(before);
    }

    @Test
    void 방송_목록은_방송_중을_먼저_종료를_뒤에_각각_최신순으로_보여주고_종료에는_종료_배지가_붙는다() throws Exception {
        Live endedOld = start("정렬-종료-오래됨");
        Live onAirOld = start("정렬-방송중-오래됨");
        Live endedNew = start("정렬-종료-최신");
        Live onAirNew = start("정렬-방송중-최신");
        end(endedOld.id(), endedOld.key());
        end(endedNew.id(), endedNew.key());

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
    void 송출_화면은_송출_키를_접속_주소에_싣지_않고_첫_메시지로_보낸다() throws Exception {
        String html = page("/live/new");

        assertThat(html).doesNotContain("?key=");
        assertThat(html).contains("type: 'auth'");
    }

    @Test
    void 송출_화면의_방송_종료_버튼은_송출_키_헤더로_종료를_요청한다() throws Exception {
        String html = page("/live/new");
        assertThat(html).contains("방송 종료", "X-Broadcast-Key", "/end");
        assertThat(html).contains("closeBroadcast()");
    }
}
