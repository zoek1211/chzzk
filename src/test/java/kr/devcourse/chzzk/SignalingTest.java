package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    JdbcTemplate jdbc;

    TestLogin.Http owner;
    TestLogin.Http other;

    @BeforeEach
    void setUp() throws Exception {
        owner = TestLogin.member(port);
        other = TestLogin.member(port);
    }

    /** 소유자가 라이브를 시작하고 그 id 를 돌려준다. */
    record Live(String id) {
    }

    Live startLive(String title) throws Exception {
        return new Live(owner.startedLiveId(title));
    }

    String latestId() {
        return String.valueOf(jdbc.queryForObject("select max(id) from live_broadcast", Long.class));
    }

    SignalingClient broadcaster(Live live) throws InterruptedException {
        return SignalingClient.broadcaster(port, live.id(), owner);
    }

    SignalingClient viewer(String liveId) {
        return SignalingClient.connect("ws://localhost:" + port + "/ws/live/" + liveId + "/viewer");
    }

    boolean isEnded(Live live) throws Exception {
        return owner.isEnded(live.id());
    }

    @Test
    void 소유자_세션은_방송자로_접속할_수_있다() throws Exception {
        Live live = startLive("소유자 접속");
        try (SignalingClient broadcaster = broadcaster(live)) {
            assertThat(broadcaster.isClosed()).isFalse();
        }
    }

    @Test
    void 비로그인은_방송자로_접속할_수_없고_방송은_그대로다() throws Exception {
        Live live = startLive("비로그인 거절");

        assertThat(SignalingClient.rejectedStatus(() -> SignalingClient.connect(SignalingClient.broadcasterUri(port, live.id())))).isEqualTo(401);
        assertThat(isEnded(live)).isFalse();
    }

    @Test
    void 다른_회원은_방송자로_접속할_수_없고_방송은_그대로다() throws Exception {
        Live live = startLive("다른 회원 거절");

        assertThat(SignalingClient.rejectedStatus(() -> SignalingClient.broadcaster(port, live.id(), other))).isEqualTo(403);
        assertThat(isEnded(live)).isFalse();
    }

    @Test
    void 종료된_라이브에는_소유자도_방송자로_접속할_수_없다() throws Exception {
        Live live = startLive("종료된 방송");
        assertThat(owner.post("/live/" + live.id() + "/end").statusCode()).isEqualTo(200);

        assertThat(SignalingClient.rejectedStatus(() -> SignalingClient.broadcaster(port, live.id(), owner))).isEqualTo(403);
    }

    @Test
    void 소유자_없는_기존_라이브에는_누구도_방송자로_접속할_수_없다() throws Exception {
        jdbc.update("insert into live_broadcast (title, status, started_at) "
                + "values ('옛날 방송 중', 'ON_AIR', current_timestamp)");
        String id = latestId();

        assertThat(SignalingClient.rejectedStatus(() -> SignalingClient.broadcaster(port, id, owner))).isEqualTo(403);
    }

    @Test
    void 없는_라이브에는_방송자도_시청자도_접속할_수_없다() throws Exception {
        assertThat(SignalingClient.rejectedStatus(() -> SignalingClient.broadcaster(port, "99999", owner))).isEqualTo(404);
        assertThat(SignalingClient.rejectedStatus(() -> viewer("99999"))).isEqualTo(404);
    }

    @Test
    void 시청자는_비로그인이든_다른_회원이든_누구나_접속할_수_있다() throws Exception {
        Live live = startLive("누구나 시청");
        try (SignalingClient broadcaster = broadcaster(live);
             SignalingClient anonymous = viewer(live.id());
             SignalingClient member = SignalingClient.connect(
                     "ws://localhost:" + port + "/ws/live/" + live.id() + "/viewer", other.cookie())) {
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
        }
    }

    @Test
    void 이미_방송자가_있으면_같은_소유자의_두_번째_연결은_거부되고_방송은_계속된다() throws Exception {
        Live live = startLive("두 번째 방송자");
        try (SignalingClient first = broadcaster(live);
             SignalingClient second = SignalingClient.connect(
                     SignalingClient.broadcasterUri(port, live.id()), owner.cookie())) {
            await().atMost(Duration.ofSeconds(5)).until(second::isClosed);
            assertThat(second.closeCode()).isEqualTo(1008);
            assertThat(isEnded(live)).isFalse();
            assertThat(first.isClosed()).isFalse();
        }
    }

    @Test
    void 시청자가_접속하면_방송자에게_시청자_식별자와_함께_입장이_알려진다() throws Exception {
        Live live = startLive("입장 알림");
        try (SignalingClient broadcaster = broadcaster(live);
             SignalingClient viewer = viewer(live.id())) {
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
        }
    }

    @Test
    void 방송자보다_먼저_들어온_시청자도_방송자가_접속하면_알려진다() throws Exception {
        Live live = startLive("먼저 입장");
        try (SignalingClient viewer = viewer(live.id());
             SignalingClient broadcaster = broadcaster(live)) {
            assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
        }
    }

    @Test
    void 방송자의_offer와_candidate는_지정한_시청자에게만_전달된다() throws Exception {
        Live live = startLive("offer 라우팅");
        try (SignalingClient broadcaster = broadcaster(live);
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
        try (SignalingClient broadcaster = broadcaster(live);
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
        try (SignalingClient broadcaster = broadcaster(live);
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
        try (SignalingClient broadcaster = broadcaster(live)) {
            SignalingClient viewer = viewer(live.id());
            String viewerId = broadcaster.next("$.viewerId");
            viewer.close();

            String left = broadcaster.next();
            assertThat((String) JsonPath.read(left, "$.type")).isEqualTo("viewer-left");
            assertThat((String) JsonPath.read(left, "$.viewerId")).isEqualTo(viewerId);
        }
    }
}
