package kr.devcourse.chzzk;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class RecordedBroadcastWebTest {

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

    @Test
    void 외부_영상_주소로_등록하면_상세_화면에서_그_영상을_재생한다() throws Exception {
        MvcResult registered = mvc.perform(multipart("/broadcasts")
                        .param("title", "어제 저녁 합방 다시보기")
                        .param("videoUrl", "https://cdn.example.com/vod/yesterday.mp4"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String detailUrl = registered.getResponse().getRedirectedUrl();

        mvc.perform(get(detailUrl))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("어제 저녁 합방 다시보기")))
                .andExpect(content().string(containsString("<video")))
                .andExpect(content().string(containsString("src=\"https://cdn.example.com/vod/yesterday.mp4\"")));
    }

    @Test
    void 목록_화면은_등록된_녹화방송을_상세_화면_링크와_함께_보여준다() throws Exception {
        String detailUrl = mvc.perform(multipart("/broadcasts")
                        .param("title", "목록에 보일 방송")
                        .param("videoUrl", "https://cdn.example.com/vod/list.mp4"))
                .andReturn().getResponse().getRedirectedUrl();

        mvc.perform(get("/broadcasts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("목록에 보일 방송")))
                .andExpect(content().string(containsString("href=\"" + detailUrl + "\"")));
    }

    @Test
    void 영상_출처가_없으면_등록하지_않고_폼에_오류를_보여준다() throws Exception {
        mvc.perform(multipart("/broadcasts").param("title", "영상 없는 방송"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("영상 파일을 올리거나 영상 주소를 입력")));

        mvc.perform(get("/broadcasts"))
                .andExpect(content().string(not(containsString("영상 없는 방송"))));
    }

    @Test
    void 영상_파일과_영상_주소를_함께_보내면_등록하지_않는다() throws Exception {
        mvc.perform(multipart("/broadcasts")
                        .file(new MockMultipartFile("videoFile", "a.mp4", "video/mp4", new byte[] {1}))
                        .param("title", "출처 두 개")
                        .param("videoUrl", "https://cdn.example.com/vod/b.mp4"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("하나만")));
    }

    @Test
    void 제목이_비어_있으면_등록하지_않는다() throws Exception {
        mvc.perform(multipart("/broadcasts")
                        .param("title", "   ")
                        .param("videoUrl", "https://cdn.example.com/vod/untitled.mp4"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("제목을 입력")));
    }

    @Test
    void 영상_주소는_http_또는_https만_받는다() throws Exception {
        mvc.perform(multipart("/broadcasts")
                        .param("title", "스크립트 주소")
                        .param("videoUrl", "javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("http")));
    }

    @Test
    void 없는_녹화방송의_상세_화면은_404다() throws Exception {
        mvc.perform(get("/broadcasts/999999")).andExpect(status().isNotFound());
    }

    @Test
    void 영상_파일을_업로드해_등록하면_상세_화면_플레이어_주소에서_그_파일을_받는다() throws Exception {
        byte[] videoBytes = "fake-mp4-bytes".getBytes();
        MvcResult registered = mvc.perform(multipart("/broadcasts")
                        .file(new MockMultipartFile("videoFile", "clip.mp4", "video/mp4", videoBytes))
                        .param("title", "업로드한 하이라이트"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String detailHtml = mvc.perform(get(registered.getResponse().getRedirectedUrl()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("업로드한 하이라이트")))
                .andReturn().getResponse().getContentAsString();

        Matcher src = Pattern.compile("<video[^>]*src=\"([^\"]+)\"").matcher(detailHtml);
        assertThat(src.find()).isTrue();

        mvc.perform(get(src.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(content().bytes(videoBytes));
    }
}
