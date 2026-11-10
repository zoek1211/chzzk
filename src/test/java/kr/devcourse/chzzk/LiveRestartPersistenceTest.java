package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 같은 데이터 디렉터리로 애플리케이션을 다시 띄웠을 때 라이브 방송 정보가 남아 있는지. */
class LiveRestartPersistenceTest {

    @TempDir
    Path dataDir;

    @Test
    void 재시작한_뒤에도_라이브_방송의_제목과_시작_시각이_조회되고_송출_키_원문은_저장소에_없다() throws Exception {
        String liveId;
        String broadcastKey;
        String watchHtmlBefore;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = mvcOf(first);
            String body = mvc.perform(post("/live")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"재시작 후에도 남을 라이브\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            liveId = String.valueOf((Integer) JsonPath.read(body, "$.id"));
            broadcastKey = JsonPath.read(body, "$.broadcastKey");
            watchHtmlBefore = mvc.perform(get("/live/" + liveId)).andReturn().getResponse().getContentAsString();
        }

        assertThat(dataFilesContain(broadcastKey)).isFalse();

        try (ConfigurableApplicationContext second = start()) {
            MockMvc mvc = mvcOf(second);
            String watchHtmlAfter = mvc.perform(get("/live/" + liveId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(watchHtmlAfter).contains("재시작 후에도 남을 라이브");
            assertThat(startedAtOf(watchHtmlAfter)).isEqualTo(startedAtOf(watchHtmlBefore));

            String list = mvc.perform(get("/broadcasts")).andReturn().getResponse().getContentAsString();
            assertThat(list).contains("재시작 후에도 남을 라이브");
        }
    }

    @Test
    void 재시작하면_방송_중이던_라이브_방송은_종료로_보이고_이미_종료된_것의_종료_시각은_그대로다() throws Exception {
        String onAirId;
        String endedId;
        String endedHtmlBefore;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = mvcOf(first);
            onAirId = startLive(mvc, "재시작에 끝날 라이브")[0];
            String[] ended = startLive(mvc, "미리 끝낸 라이브");
            endedId = ended[0];
            mvc.perform(post("/live/" + endedId + "/end").header("X-Broadcast-Key", ended[1]))
                    .andExpect(status().isOk());
            endedHtmlBefore = mvc.perform(get("/live/" + endedId)).andReturn().getResponse().getContentAsString();
            assertThat(LivePages.showsEnded(
                    mvc.perform(get("/live/" + onAirId)).andReturn().getResponse().getContentAsString())).isFalse();
        }

        Thread.sleep(1100);

        try (ConfigurableApplicationContext second = start()) {
            MockMvc mvc = mvcOf(second);
            assertThat(LivePages.showsEnded(
                    mvc.perform(get("/live/" + onAirId)).andReturn().getResponse().getContentAsString())).isTrue();
            assertThat(mvc.perform(get("/live/" + endedId)).andReturn().getResponse().getContentAsString())
                    .isEqualTo(endedHtmlBefore);
        }
    }

    private static String[] startLive(MockMvc mvc, String title) throws Exception {
        String body = mvc.perform(post("/live")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return new String[]{String.valueOf((Integer) JsonPath.read(body, "$.id")), JsonPath.read(body, "$.broadcastKey")};
    }

    private static String startedAtOf(String html) {
        return html.replaceAll("(?s).*?<time[^>]*>([^<]+)</time>.*", "$1");
    }

    private boolean dataFilesContain(String text) throws Exception {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(dataDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (indexOf(Files.readAllBytes(file), needle) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private ConfigurableApplicationContext start() {
        // 커맨드라인 인자로 넘겨야 application.properties 의 기본 데이터 디렉터리보다 우선한다.
        return new SpringApplicationBuilder(ChzzkApplication.class)
                .run("--chzzk.data-dir=" + dataDir, "--server.port=0");
    }

    private static MockMvc mvcOf(ConfigurableApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
    }
}
