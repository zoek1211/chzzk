package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 라이브를 시작했는데 방송자가 유예 시간 안에 접속하지 않으면 종료가 되는지. 테스트에서는 유예 시간을 짧게 둔다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveJoinTimeoutTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
        registry.add("chzzk.live.broadcaster-join-timeout", () -> "1s");
    }

    @LocalServerPort
    int port;

    TestLogin.Http owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = TestLogin.member(port);
    }

    @Test
    void 방송자가_유예_시간_안에_접속하지_않으면_라이브가_종료가_된다() throws Exception {
        String live = owner.startedLiveId("유예 시간");
        assertThat(owner.isEnded(live)).isFalse();

        await().atMost(Duration.ofSeconds(8)).until(() -> owner.isEnded(live));
    }

    @Test
    void 유예_시간_안에_접속한_방송자가_있으면_종료가_되지_않는다() throws Exception {
        String live = owner.startedLiveId("유예 시간");
        try (SignalingClient broadcaster = SignalingClient.broadcaster(port, live, owner)) {
            Thread.sleep(2500);

            assertThat(owner.isEnded(live)).isFalse();
        }
    }

    @Test
    void 시청자만_있고_방송자가_접속하지_않아도_유예_시간이_지나면_종료가_되고_시청자에게_알려진다() throws Exception {
        String live = owner.startedLiveId("유예 시간");
        try (SignalingClient viewer = SignalingClient.connect(
                "ws://localhost:" + port + "/ws/live/" + live + "/viewer")) {
            await().atMost(Duration.ofSeconds(8)).until(viewer::isClosed);
            assertThat(owner.isEnded(live)).isTrue();
        }
    }
}
