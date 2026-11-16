package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 클러스터 모드 라이브: 방송자와 시청자가 다른 인스턴스에 붙어도 동작하고, 인스턴스가 사라져도 라이브가 이어진다. */
@Testcontainers(disabledWithoutDocker = true)
class ClusterLiveTest {

    @TempDir
    Path dataDir;

    private static SignalingClient viewer(ClusterHarness.Instance instance, String live) {
        return SignalingClient.connect("ws://localhost:" + instance.port() + "/ws/live/" + live + "/viewer");
    }

    @Test
    void 방송자는_A에_시청자는_B에_붙어도_viewer_joined_offer_answer_candidate가_오간다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=1h");
            var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=1h");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("교차 인스턴스");

            try (var broadcaster = SignalingClient.broadcaster(a.port(), live, owner);
                    var viewer = viewer(b, live)) {
                String joinedMessage = broadcaster.next();
                assertThat((String) JsonPath.read(joinedMessage, "$.type")).isEqualTo("viewer-joined");
                String viewerId = JsonPath.read(joinedMessage, "$.viewerId");

                broadcaster.send("{\"type\":\"offer\",\"viewerId\":\"" + viewerId + "\",\"sdp\":\"o\"}");
                String offer = viewer.next();
                assertThat((String) JsonPath.read(offer, "$.type")).isEqualTo("offer");
                assertThat((String) JsonPath.read(offer, "$.sdp")).isEqualTo("o");
                assertThat(offer).doesNotContain("viewerId");

                viewer.send("{\"type\":\"answer\",\"sdp\":\"a\"}");
                String answer = broadcaster.next();
                assertThat((String) JsonPath.read(answer, "$.type")).isEqualTo("answer");
                assertThat((String) JsonPath.read(answer, "$.viewerId")).isEqualTo(viewerId);

                viewer.send("{\"type\":\"candidate\",\"candidate\":{\"c\":1}}");
                assertThat((String) JsonPath.read((String) broadcaster.next(), "$.type")).isEqualTo("candidate");
                broadcaster.send("{\"type\":\"candidate\",\"viewerId\":\"" + viewerId + "\",\"candidate\":{\"c\":2}}");
                assertThat((String) viewer.next("$.type")).isEqualTo("candidate");
            }
        }
    }

    @Test
    void 방송자보다_늦게_다른_인스턴스에서_접속해도_이미_있는_시청자를_받는다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=1h");
            var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=1h");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("먼저 온 시청자");

            try (var viewer = viewer(a, live);
                    var broadcaster = SignalingClient.broadcaster(b.port(), live, b.withSession(owner))) {
                assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
            }
        }
    }

    @Test
    void 다른_인스턴스에_이미_방송자가_있으면_새_방송자_연결은_닫힌다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=1h");
            var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=1h");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("방송자는 한 명");

            try (var first = SignalingClient.broadcaster(a.port(), live, owner);
                    var second = SignalingClient.connect(
                            SignalingClient.broadcasterUri(b.port(), live), b.withSession(owner).cookie())) {
                await().atMost(Duration.ofSeconds(5)).until(second::isClosed);
                assertThat(second.closeCode()).isEqualTo(1008);
                assertThat(first.isClosed()).isFalse();
            }
        }
    }

    @Test
    void A와_B에_걸친_시청자가_정원을_넘으면_full을_받는다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.viewer-capacity=2", "--chzzk.live.broadcaster-join-timeout=1h");
            var b = cluster.start("B", "--chzzk.live.viewer-capacity=2", "--chzzk.live.broadcaster-join-timeout=1h");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("정원");

            try (var broadcaster = SignalingClient.broadcaster(a.port(), live, owner);
                    var v1 = viewer(a, live);
                    var v2 = viewer(b, live)) {
                broadcaster.next();
                broadcaster.next();

                try (var v3 = viewer(a, live); var v4 = viewer(b, live)) {
                    assertThat((String) v3.next("$.type")).isEqualTo("full");
                    assertThat((String) v4.next("$.type")).isEqualTo("full");
                }
                v2.close();
                assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-left");
                try (var v5 = viewer(b, live)) {
                    assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
                    assertThat(v5.receivesNothing()).isTrue();
                }
            }
        }
    }

    @Test
    void A에서_방송을_종료하면_B의_시청자가_ended를_받고_연결이_닫힌다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=1h");
            var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=1h");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("종료 전파");

            try (var broadcaster = SignalingClient.broadcaster(a.port(), live, owner);
                    var viewer = viewer(b, live)) {
                broadcaster.next();
                assertThat(owner.post("/live/" + live + "/end").statusCode()).isEqualTo(200);

                assertThat((String) viewer.next("$.type")).isEqualTo("ended");
                assertThat((String) broadcaster.next("$.type")).isEqualTo("ended");
                await().atMost(Duration.ofSeconds(5)).until(viewer::isClosed);
                assertThat(viewer.closeCode()).isEqualTo(1000);
                assertThat(b.anonymous().isEnded(live)).isTrue();
            }
        }
    }

    @Test
    void 시청자가_붙은_인스턴스를_닫아도_라이브는_방송_중이고_시청자는_재접속_코드를_받는다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=1h");
            var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=1h");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("시청자 인스턴스 교체");

            try (var broadcaster = SignalingClient.broadcaster(a.port(), live, owner);
                    var viewer = viewer(b, live)) {
                assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");

                b.close();

                await().atMost(Duration.ofSeconds(5)).until(viewer::isClosed);
                assertThat(viewer.closeCode()).isEqualTo(1012);
                assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-left");
                assertThat(a.anonymous().isEnded(live)).isFalse();
                try (var again = viewer(a, live)) {
                    assertThat((String) broadcaster.next("$.type")).isEqualTo("viewer-joined");
                }
            }
        }
    }

    @Test
    void 방송자가_붙은_인스턴스를_닫고_유예_안에_다른_인스턴스로_재접속하면_방송이_유지된다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=3s");
            var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=3s");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("방송자 인스턴스 교체");

            try (var viewer = viewer(b, live)) {
                var broadcaster = SignalingClient.broadcaster(a.port(), live, owner);
                broadcaster.next();

                a.close();

                await().atMost(Duration.ofSeconds(5)).until(broadcaster::isClosed);
                assertThat(broadcaster.closeCode()).isEqualTo(1012);
                try (var again = SignalingClient.broadcaster(b.port(), live, b.withSession(owner))) {
                    assertThat((String) again.next("$.type")).isEqualTo("viewer-joined");
                    Thread.sleep(5000); // 유예 시간(3초)보다 오래 기다려도 방송자가 붙어 있으면 유지된다.
                    assertThat(b.anonymous().isEnded(live)).isFalse();
                    assertThat(viewer.isClosed()).isFalse();
                }
            }
        }
    }

    @Test
    void 방송자가_유예_시간보다_오래_접속하지_않으면_종료되고_시청자가_ended를_받는다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=2s");
            var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=2s");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("유예 초과");

            try (var viewer = viewer(b, live)) {
                assertThat(b.anonymous().isEnded(live)).isFalse();

                await().atMost(Duration.ofSeconds(10)).until(() -> b.anonymous().isEnded(live));
                assertThat((String) viewer.next("$.type")).isEqualTo("ended");
            }
        }
    }

    private static StringRedisTemplate redis(ClusterHarness.Instance instance) {
        return instance.context().getBean(StringRedisTemplate.class);
    }

    private static String presenceKey(ClusterHarness.Instance instance, String live) {
        return instance.context().getEnvironment().getProperty("chzzk.cluster.redis-prefix") + "live:" + live + ":presence";
    }

    @Test
    void 연결을_쥔_채_접속_기록_키가_만료돼도_되살려서_방송이_끝나지_않는다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=2s");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("키 만료");

            try (var broadcaster = SignalingClient.broadcaster(a.port(), live, owner)) {
                broadcaster.next();
                redis(a).delete(presenceKey(a, live));

                Thread.sleep(5000); // 유예 시간(2초)보다 오래 기다려도 갱신이 기록을 되살린다.

                assertThat(a.anonymous().isEnded(live)).isFalse();
                assertThat(broadcaster.isClosed()).isFalse();
                assertThat(redis(a).hasKey(presenceKey(a, live))).isTrue();
            }
        }
    }

    @Test
    void 접속_기록을_다른_방송자가_쥐고_있으면_이쪽_방송자_연결은_중복으로_닫히고_방송은_유지된다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=1h");
            TestLogin.Http owner = a.member();
            String live = owner.startedLiveId("중복 방송자");

            try (var broadcaster = SignalingClient.broadcaster(a.port(), live, owner)) {
                broadcaster.next();
                redis(a).opsForValue().set(presenceKey(a, live), "B-other|session-x");

                await().atMost(Duration.ofSeconds(8)).until(broadcaster::isClosed);
                assertThat(broadcaster.closeCode()).isEqualTo(1008);
                assertThat(a.anonymous().isEnded(live)).isFalse();
            }
        }
    }
}
