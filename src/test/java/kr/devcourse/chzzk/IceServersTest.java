package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** ICE 서버(STUN 기본값, 선택적 TURN) 설정이 송출·시청 화면에 내려가는지 확인한다. */
class IceServersTest {

    static String render(WebApplicationContext context, String path) throws Exception {
        MockMvc mvc = TestWeb.mvc(context);
        return mvc.perform(get(path).session(TestLogin.member(mvc))).andReturn().getResponse().getContentAsString();
    }

    static String liveId(WebApplicationContext context) throws Exception {
        MockMvc mvc = TestWeb.mvc(context);
        String body = mvc.perform(LiveBroadcastWebTest.startLive("ICE 확인").session(TestLogin.member(mvc)))
                .andReturn().getResponse().getContentAsString();
        return LiveBroadcastWebTest.startedId(body);
    }

    @Nested
    @SpringBootTest
    class 기본값 {

        @TempDir
        static Path dataDir;

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registry.add("chzzk.data-dir", dataDir::toString);
        }

        @Autowired
        WebApplicationContext context;

        @Test
        void 송출_화면과_시청_화면은_공개_STUN만_내려받고_TURN은_없다() throws Exception {
            String broadcast = render(context, "/live/new");
            String watch = render(context, "/live/" + liveId(context));

            assertThat(broadcast).contains("stun:stun.l.google.com:19302");
            assertThat(watch).contains("stun:stun.l.google.com:19302");
            assertThat(broadcast).doesNotContain("turn:");
            assertThat(watch).doesNotContain("turn:");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "chzzk.ice.turn-url=turn:turn.example.com:3478",
            "chzzk.ice.turn-username=user1",
            "chzzk.ice.turn-credential=secret1"})
    class TURN_설정 {

        @TempDir
        static Path dataDir;

        @DynamicPropertySource
        static void props(DynamicPropertyRegistry registry) {
            registry.add("chzzk.data-dir", dataDir::toString);
        }

        @Autowired
        WebApplicationContext context;

        @Test
        void TURN을_설정하면_송출_화면과_시청_화면에_함께_내려간다() throws Exception {
            String broadcast = render(context, "/live/new");
            String watch = render(context, "/live/" + liveId(context));

            for (String html : new String[] {broadcast, watch}) {
                assertThat(html).contains("stun:stun.l.google.com:19302",
                        "turn:turn.example.com:3478", "user1", "secret1");
            }
        }
    }
}
