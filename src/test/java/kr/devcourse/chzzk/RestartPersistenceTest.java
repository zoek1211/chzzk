package kr.devcourse.chzzk;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 같은 데이터 디렉터리로 애플리케이션을 다시 띄웠을 때 등록 정보와 업로드 영상이 남아 있는지. */
class RestartPersistenceTest {

    @TempDir
    Path dataDir;

    @Test
    void 재시작한_뒤에도_등록한_녹화방송과_업로드_영상이_남아_있다() throws Exception {
        byte[] videoBytes = "persisted-video".getBytes();
        String detailUrl;
        String videoUrl;

        try (ConfigurableApplicationContext first = start()) {
            MockMvc mvc = mvcOf(first);
            detailUrl = mvc.perform(multipart("/broadcasts")
                            .file(new MockMultipartFile("videoFile", "replay.mp4", "video/mp4", videoBytes))
                            .param("title", "재시작 후에도 남을 방송"))
                    .andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
            String html = mvc.perform(get(detailUrl)).andReturn().getResponse().getContentAsString();
            videoUrl = html.replaceAll("(?s).*<video[^>]*src=\"([^\"]+)\".*", "$1");
        }

        try (ConfigurableApplicationContext second = start()) {
            MockMvc mvc = mvcOf(second);
            mvc.perform(get("/broadcasts"))
                    .andExpect(content().string(containsString("재시작 후에도 남을 방송")));
            mvc.perform(get(detailUrl))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("src=\"" + videoUrl + "\"")));
            mvc.perform(get(videoUrl))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(videoBytes));
        }
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
