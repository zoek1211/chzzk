package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class LiveBroadcastWebTest {

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
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 제목을_보내_라이브를_시작하면_라이브_방송_id와_송출_키_원문을_돌려준다() throws Exception {
        mvc.perform(startLive("첫 라이브"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.broadcastKey").isNotEmpty());
    }

    @Test
    void 공백만_있는_제목으로는_라이브를_시작할_수_없다() throws Exception {
        mvc.perform(startLive("   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 방송_목록은_라이브_방송_섹션과_녹화방송_섹션을_함께_보여주고_라이브에는_방송_중_배지가_붙는다() throws Exception {
        String recordedUrl = mvc.perform(multipart("/broadcasts")
                        .param("title", "목록의 녹화방송")
                        .param("videoUrl", "https://cdn.example.com/vod/rec.mp4"))
                .andReturn().getResponse().getRedirectedUrl();
        String liveId = startedId(mvc.perform(startLive("목록의 라이브")).andReturn().getResponse().getContentAsString());

        String html = mvc.perform(get("/broadcasts"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("라이브 방송", "녹화방송", "목록의 라이브", "목록의 녹화방송",
                "href=\"/live/" + liveId + "\"", "href=\"" + recordedUrl + "\"", "방송 중");
        assertThat(html.indexOf("목록의 라이브")).isLessThan(html.indexOf("목록의 녹화방송"));
    }

    @Test
    void 시청_화면은_라이브_방송의_제목과_방송_상태를_보여준다() throws Exception {
        String liveId = startedId(mvc.perform(startLive("시청할 라이브")).andReturn().getResponse().getContentAsString());

        mvc.perform(get("/live/" + liveId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("시청할 라이브")))
                .andExpect(content().string(containsString("방송 중")));
    }

    @Test
    void 없는_라이브_방송의_시청_화면은_404다() throws Exception {
        mvc.perform(get("/live/999999")).andExpect(status().isNotFound());
    }

    @Test
    void 송출_화면은_제목_입력과_카메라_마이크_미리보기와_방송_시작_버튼을_갖는다() throws Exception {
        mvc.perform(get("/live/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"title\"")))
                .andExpect(content().string(containsString("<video")))
                .andExpect(content().string(containsString("getUserMedia")))
                .andExpect(content().string(containsString("방송 시작")));
    }

    @Test
    void 송출_화면은_카메라_전환_버튼과_화질_제한과_HTTPS_안내를_갖는다() throws Exception {
        mvc.perform(get("/live/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"switch-camera-button\"")))
                .andExpect(content().string(containsString("facingMode")))
                .andExpect(content().string(containsString("frameRate: { max: 30 }")))
                .andExpect(content().string(containsString("HTTPS")));
    }

    @Test
    void 시청_화면은_음소거_해제_버튼을_갖는다() throws Exception {
        String liveId = startedId(mvc.perform(startLive("음소거 라이브")).andReturn().getResponse().getContentAsString());

        mvc.perform(get("/live/" + liveId))
                .andExpect(content().string(containsString("id=\"unmute-button\"")))
                .andExpect(content().string(containsString("음소거 해제")));
    }

    @Test
    void 송출_키는_방송_목록과_시청_화면에_나타나지_않는다() throws Exception {
        String body = mvc.perform(startLive("키 비공개 라이브")).andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.broadcastKey");
        String liveId = startedId(body);

        mvc.perform(get("/broadcasts"))
                .andExpect(content().string(not(containsString(key))));
        mvc.perform(get("/live/" + liveId))
                .andExpect(content().string(not(containsString(key))));
    }

    @Test
    void 송출_화면에서_헤더로_갈_수_있다() throws Exception {
        mvc.perform(get("/broadcasts"))
                .andExpect(content().string(containsString("href=\"/live/new\"")));
    }

    static String startedId(String json) {
        return String.valueOf((Integer) JsonPath.read(json, "$.id"));
    }

    static MockHttpServletRequestBuilder startLive(String title) {
        return post("/live")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}");
    }
}
