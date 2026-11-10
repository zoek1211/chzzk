package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** WebSocket 시그널링 경계: 실제 포트로 앱을 띄우고 방송자·시청자를 흉내 낸다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignalingTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @LocalServerPort
    int port;

    record Live(String id, String key) {
    }

    Live startLive(String title) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"" + title + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return new Live(String.valueOf((Object) JsonPath.read(response.body(), "$.id")),
                JsonPath.read(response.body(), "$.broadcastKey"));
    }

    SignalingClient broadcaster(Live live, String key) {
        return SignalingClient.connect(
                "ws://localhost:" + port + "/ws/live/" + live.id() + "/broadcaster?key=" + key);
    }

    SignalingClient viewer(String liveId) {
        return SignalingClient.connect("ws://localhost:" + port + "/ws/live/" + liveId + "/viewer");
    }

    @Test
    void 방송자는_올바른_송출_키로_접속할_수_있다() throws Exception {
        Live live = startLive("키 확인");
        try (SignalingClient broadcaster = broadcaster(live, live.key())) {
            assertThat(broadcaster.isClosed()).isFalse();
        }
    }

    @Test
    void 틀린_송출_키로는_방송자로_접속할_수_없다() throws Exception {
        Live live = startLive("틀린 키");
        assertThatThrownBy(() -> broadcaster(live, "wrong-key")).isNotNull();
    }

    @Test
    void 종료된_라이브_방송에는_올바른_송출_키로도_방송자로_접속할_수_없다() throws Exception {
        Live live = startLive("종료된 방송");
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live/" + live.id() + "/end"))
                        .header("X-Broadcast-Key", live.key())
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());

        assertThatThrownBy(() -> broadcaster(live, live.key())).isNotNull();
    }

    @Test
    void 없는_라이브_방송으로는_방송자도_시청자도_접속할_수_없다() {
        assertThatThrownBy(() -> broadcaster(new Live("99999", "x"), "x")).isNotNull();
        assertThatThrownBy(() -> viewer("99999")).isNotNull();
    }

    @Test
    void 시청자가_접속하면_방송자에게_시청자_식별자와_함께_입장이_알려진다() throws Exception {
        Live live = startLive("입장 알림");
        try (SignalingClient broadcaster = broadcaster(live, live.key());
             SignalingClient viewer = viewer(live.id())) {
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
        }
    }

    @Test
    void 방송자보다_먼저_들어온_시청자도_방송자가_접속하면_알려진다() throws Exception {
        Live live = startLive("먼저 입장");
        try (SignalingClient viewer = viewer(live.id());
             SignalingClient broadcaster = broadcaster(live, live.key())) {
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
        }
    }

    @Test
    void 방송자의_offer와_candidate는_지정한_시청자에게만_전달된다() throws Exception {
        Live live = startLive("offer 라우팅");
        try (SignalingClient broadcaster = broadcaster(live, live.key());
             SignalingClient first = viewer(live.id());
             SignalingClient second = viewer(live.id())) {
            String firstId = broadcaster.next("$.viewerId");
            String secondId = broadcaster.next("$.viewerId");
            assertThat(firstId).isNotEqualTo(secondId);

            broadcaster.send("{\"type\":\"offer\",\"viewerId\":\"" + secondId + "\",\"sdp\":\"offer-sdp\"}");
            broadcaster.send("{\"type\":\"candidate\",\"viewerId\":\"" + secondId + "\",\"candidate\":{\"candidate\":\"c1\"}}");

            String offer = second.next();
            assertThat((String) JsonPath.read(offer, "$.type")).isEqualTo("offer");
            assertThat((String) JsonPath.read(offer, "$.sdp")).isEqualTo("offer-sdp");
            assertThat((String) second.next("$.candidate.candidate")).isEqualTo("c1");
            assertThat(first.receivesNothing()).isTrue();
        }
    }

    @Test
    void 시청자의_answer와_candidate는_방송자에게만_전달되고_식별자가_붙는다() throws Exception {
        Live live = startLive("answer 라우팅");
        try (SignalingClient broadcaster = broadcaster(live, live.key());
             SignalingClient viewer = viewer(live.id());
             SignalingClient other = viewer(live.id())) {
            String viewerId = broadcaster.next("$.viewerId");
            broadcaster.next();

            viewer.send("{\"type\":\"answer\",\"sdp\":\"answer-sdp\"}");
            viewer.send("{\"type\":\"candidate\",\"candidate\":{\"candidate\":\"c2\"}}");

            String answer = broadcaster.next();
            assertThat((String) JsonPath.read(answer, "$.type")).isEqualTo("answer");
            assertThat((String) JsonPath.read(answer, "$.sdp")).isEqualTo("answer-sdp");
            assertThat((String) JsonPath.read(answer, "$.viewerId")).isEqualTo(viewerId);
            assertThat((String) broadcaster.next("$.candidate.candidate")).isEqualTo("c2");
            assertThat(other.receivesNothing()).isTrue();
        }
    }

    @Test
    void 시청자가_다른_시청자_행세를_하는_offer는_전달되지_않는다() throws Exception {
        Live live = startLive("권한");
        try (SignalingClient broadcaster = broadcaster(live, live.key());
             SignalingClient viewer = viewer(live.id());
             SignalingClient other = viewer(live.id())) {
            String otherId = broadcaster.next("$.viewerId");
            broadcaster.next();

            viewer.send("{\"type\":\"offer\",\"viewerId\":\"" + otherId + "\",\"sdp\":\"fake\"}");

            assertThat(other.receivesNothing()).isTrue();
            assertThat(broadcaster.receivesNothing()).isTrue();
        }
    }

    @Test
    void 시청자_연결이_끊기면_방송자에게_퇴장이_알려진다() throws Exception {
        Live live = startLive("퇴장 알림");
        try (SignalingClient broadcaster = broadcaster(live, live.key())) {
            SignalingClient viewer = viewer(live.id());
            String viewerId = broadcaster.next("$.viewerId");
            viewer.close();

            String left = broadcaster.next();
            assertThat((String) JsonPath.read(left, "$.type")).isEqualTo("viewer-left");
            assertThat((String) JsonPath.read(left, "$.viewerId")).isEqualTo(viewerId);
        }
    }
}
