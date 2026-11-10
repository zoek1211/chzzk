package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
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

    final HttpClient http = HttpClient.newHttpClient();

    record Live(String id, String key) {
    }

    Live startLive() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"종료 테스트\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return new Live(String.valueOf((Object) JsonPath.read(response.body(), "$.id")),
                JsonPath.read(response.body(), "$.broadcastKey"));
    }

    SignalingClient broadcaster(Live live) throws InterruptedException {
        return SignalingClient.authenticatedBroadcaster(port, live.id(), live.key());
    }

    SignalingClient viewer(Live live) {
        return SignalingClient.connect("ws://localhost:" + port + "/ws/live/" + live.id() + "/viewer");
    }

    boolean isEnded(Live live) throws Exception {
        return LivePages.showsEnded(http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live/" + live.id())).build(),
                HttpResponse.BodyHandlers.ofString()).body());
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
    void 송출_키로_종료_요청을_해도_시청자에게_종료가_알려지고_연결이_닫힌다() throws Exception {
        Live live = startLive();
        try (SignalingClient broadcaster = broadcaster(live); SignalingClient viewer = viewer(live)) {
            broadcaster.next("$.type");

            http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live/" + live.id() + "/end"))
                    .header("X-Broadcast-Key", live.key())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());

            assertThat(viewer.<String>next("$.type")).isEqualTo("ended");
            await().atMost(Duration.ofSeconds(5)).until(viewer::isClosed);
            assertThat(isEnded(live)).isTrue();
        }
    }

    @Test
    void 다른_방송이_종료되어도_이_방송의_시청자는_영향을_받지_않는다() throws Exception {
        Live ended = startLive();
        Live other = startLive();
        try (SignalingClient otherBroadcaster = broadcaster(other); SignalingClient otherViewer = viewer(other)) {
            otherBroadcaster.next("$.type");
            SignalingClient broadcaster = broadcaster(ended);
            broadcaster.close();
            await().atMost(Duration.ofSeconds(5)).until(() -> isEnded(ended));

            assertThat(otherViewer.receivesNothing()).isTrue();
            assertThat(otherViewer.isClosed()).isFalse();
        }
    }
}
