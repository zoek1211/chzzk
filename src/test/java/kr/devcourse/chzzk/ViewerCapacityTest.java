package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 시청 정원: 시그널링 경계에서 정원 초과 거부와 자리 반환을 검증한다. 테스트에서는 정원을 2로 둔다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ViewerCapacityTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
        registry.add("chzzk.live.viewer-capacity", () -> "2");
    }

    @LocalServerPort
    int port;

    String[] startLive() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"정원\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return new String[] {String.valueOf((Object) JsonPath.read(response.body(), "$.id")),
                JsonPath.read(response.body(), "$.broadcastKey")};
    }

    SignalingClient broadcaster(String[] live) {
        return SignalingClient.connect(
                "ws://localhost:" + port + "/ws/live/" + live[0] + "/broadcaster?key=" + live[1]);
    }

    SignalingClient viewer(String[] live) {
        return SignalingClient.connect("ws://localhost:" + port + "/ws/live/" + live[0] + "/viewer");
    }

    @Test
    void 정원이_찬_방송의_다음_시청자는_full_메시지와_함께_거부되고_방송자는_모른다() throws Exception {
        String[] live = startLive();
        try (SignalingClient broadcaster = broadcaster(live);
                SignalingClient v1 = viewer(live);
                SignalingClient v2 = viewer(live)) {
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");

            try (SignalingClient v3 = viewer(live)) {
                assertThat((String) v3.next("$.type")).isEqualTo("full");
                Thread.sleep(300);
                assertThat(v3.isClosed()).isTrue();
                assertThat(broadcaster.receivesNothing()).isTrue();
            }
        }
    }

    @Test
    void 시청자가_나가면_새_시청자가_들어올_수_있다() throws Exception {
        String[] live = startLive();
        try (SignalingClient broadcaster = broadcaster(live);
                SignalingClient v1 = viewer(live);
                SignalingClient v2 = viewer(live)) {
            broadcaster.next();
            broadcaster.next();

            v1.close();
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-left");

            try (SignalingClient v3 = viewer(live)) {
                assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
                assertThat(v3.receivesNothing()).isTrue();
                assertThat(v3.isClosed()).isFalse();
            }
        }
    }

    @Test
    void 정원은_방송마다_따로_센다() throws Exception {
        String[] full = startLive();
        String[] other = startLive();
        try (SignalingClient b = broadcaster(other);
                SignalingClient a1 = viewer(full);
                SignalingClient a2 = viewer(full);
                SignalingClient o1 = viewer(other)) {
            assertThat((String) b.next("$.type")).isEqualTo("viewer-joined");
            assertThat(o1.receivesNothing()).isTrue();
        }
    }
}
