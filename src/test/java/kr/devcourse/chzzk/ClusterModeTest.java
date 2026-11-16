package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 클러스터 모드: 인스턴스 A·B가 같은 Postgres·Redis·영상 디렉터리를 공유한다. */
@Testcontainers(disabledWithoutDocker = true)
class ClusterModeTest {

    @TempDir
    Path dataDir;

    @Test
    void A에서_로그인한_세션으로_B에서도_로그인_전용_화면이_열린다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A");
            var b = cluster.start("B");
            TestLogin.Http onA = a.member();

            assertThat(b.anonymous().get("/broadcasts/new").statusCode()).isEqualTo(302);
            assertThat(b.withSession(onA).get("/broadcasts/new").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void A에서_등록한_녹화방송과_댓글이_B에_보이고_올린_영상을_B에서_구간_재생한다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A");
            var b = cluster.start("B");
            TestLogin.Http onA = a.member();
            byte[] video = "0123456789".getBytes(StandardCharsets.UTF_8);

            String detail = upload(onA, "공유 방송", video);
            HttpResponse<String> comment = onA.send(onA.request(detail + "/comments")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("body=A에서+쓴+댓글")));
            assertThat(comment.statusCode()).isEqualTo(302);

            assertThat(b.anonymous().get("/broadcasts").body()).contains("공유 방송");
            String page = b.anonymous().get(detail).body();
            assertThat(page).contains("A에서 쓴 댓글");
            Matcher src = Pattern.compile("<video[^>]*src=\"([^\"]+)\"").matcher(page);
            assertThat(src.find()).isTrue();
            URI videoUri = URI.create("http://localhost:" + b.port() + src.group(1));

            HttpResponse<byte[]> whole = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(videoUri).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(whole.statusCode()).isEqualTo(200);
            assertThat(whole.body()).isEqualTo(video);
            HttpResponse<byte[]> range = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(videoUri).header("Range", "bytes=2-5").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(range.statusCode()).isEqualTo(206);
            assertThat(range.body()).isEqualTo("2345".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void 방송_중인_라이브가_있어도_새_인스턴스가_뜨면_종료되지_않는다() throws Exception {
        try (var cluster = ClusterHarness.newCluster(dataDir)) {
            var a = cluster.start("A", "--chzzk.live.broadcaster-join-timeout=1h");
            String live = a.member().startedLiveId("계속되는 방송");

            var c = cluster.start("C", "--chzzk.live.broadcaster-join-timeout=1h");

            assertThat(a.anonymous().isEnded(live)).isFalse();
            assertThat(c.anonymous().get("/live/" + live).statusCode()).isEqualTo(200);
            assertThat(c.anonymous().isEnded(live)).isFalse();
        }
    }

    /** multipart 로 녹화방송을 등록하고 상세 화면 경로를 돌려준다. */
    private static String upload(TestLogin.Http member, String title, byte[] video) throws Exception {
        String boundary = "----chzzk" + System.nanoTime();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n" + title + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"videoFile\"; filename=\"v.mp4\"\r\n"
                + "Content-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(video);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = member.send(member.request("/broadcasts")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
        assertThat(response.statusCode()).isEqualTo(302);
        return URI.create(response.headers().firstValue("Location").orElseThrow()).getPath();
    }
}
