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

/** 라이브 방송 종료(연결 끊김·종료 요청)가 시청자에게 알려지는 시그널링 경계. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveEndSignalingTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @LocalServerPort
    int port;

    TestLogin.Http owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = TestLogin.member(port);
    }

    record Live(String id) {
    }

    Live startLive() throws Exception {
        return new Live(owner.startedLiveId("종료 테스트"));
    }

    SignalingClient broadcaster(Live live) throws InterruptedException {
        return SignalingClient.broadcaster(port, live.id(), owner);
    }

    SignalingClient viewer(Live live) {
        return SignalingClient.connect("ws://localhost:" + port + "/ws/live/" + live.id() + "/viewer");
    }

    boolean isEnded(Live live) throws Exception {
        return owner.isEnded(live.id());
    }

    @Test
    void 방송자_연결이_끊기면_방송_상태가_종료가_된다() throws Exception {
        Live live = startLive();
        SignalingClient broadcaster = broadcaster(live);
        assertThat(isEnded(live)).isFalse();

        broadcaster.close();

        await().atMost(Duration.ofSeconds(5)).until(() -> isEnded(live));
    }

    @Test
    void 방송자_연결이_끊기면_모든_시청자에게_종료가_알려지고_시청자_연결이_닫힌다() throws Exception {
        Live live = startLive();
        SignalingClient broadcaster = broadcaster(live);
        try (SignalingClient v1 = viewer(live); SignalingClient v2 = viewer(live)) {
            broadcaster.next("$.type");
            broadcaster.next("$.type");

            broadcaster.close();

            assertThat(v1.<String>next("$.type")).isEqualTo("ended");
            assertThat(v2.<String>next("$.type")).isEqualTo("ended");
            await().atMost(Duration.ofSeconds(5)).until(() -> v1.isClosed() && v2.isClosed());
        }
    }

    @Test
    void 소유자가_종료_요청을_해도_시청자에게_종료가_알려지고_연결이_닫힌다() throws Exception {
        Live live = startLive();
        try (SignalingClient broadcaster = broadcaster(live); SignalingClient viewer = viewer(live)) {
            broadcaster.next("$.type");

            assertThat(owner.post("/live/" + live.id() + "/end").statusCode()).isEqualTo(200);

            assertThat(viewer.<String>next("$.type")).isEqualTo("ended");
            await().atMost(Duration.ofSeconds(5)).until(viewer::isClosed);
            assertThat(isEnded(live)).isTrue();
        }
    }

    @Test
    void 같은_계정의_다른_기기에서_종료해도_방송자_연결에_종료가_알려지고_닫힌다() throws Exception {
        Live live = startLive();
        try (SignalingClient broadcaster = broadcaster(live)) {
            TestLogin.Http otherDevice = new TestLogin.Http(port, owner.sessionCookie());

            assertThat(otherDevice.post("/live/" + live.id() + "/end").statusCode()).isEqualTo(200);

            assertThat(broadcaster.<String>next("$.type")).isEqualTo("ended");
            await().atMost(Duration.ofSeconds(5)).until(broadcaster::isClosed);
        }
    }

    @Test
    void 다른_방송이_종료되어도_이_방송의_시청자는_영향을_받지_않는다() throws Exception {
        Live ended = startLive();
        TestLogin.Http otherOwner = TestLogin.member(port);
        Live other = new Live(otherOwner.startedLiveId("다른 방송"));
        try (SignalingClient otherBroadcaster = SignalingClient.broadcaster(port, other.id(), otherOwner);
             SignalingClient otherViewer = viewer(other)) {
            otherBroadcaster.next("$.type");
            SignalingClient broadcaster = broadcaster(ended);
            broadcaster.close();
            await().atMost(Duration.ofSeconds(5)).until(() -> isEnded(ended));

            assertThat(otherViewer.receivesNothing()).isTrue();
            assertThat(otherViewer.isClosed()).isFalse();
        }
    }
}
