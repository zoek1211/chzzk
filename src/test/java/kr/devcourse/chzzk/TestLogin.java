package kr.devcourse.chzzk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** 테스트 도우미: 실제 가입 흐름으로 회원을 만들고 그 로그인 세션을 MockMvc나 실제 HTTP/WebSocket 요청에 싣는다. */
final class TestLogin {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String CSRF = "test-csrf-token";

    private TestLogin() {
    }

    static String newLoginId() {
        return "live" + SEQ.incrementAndGet() + "x";
    }

    /** MockMvc: 새 회원으로 가입하고(가입하면 바로 로그인) 그 세션을 돌려준다. */
    static MockHttpSession member(MockMvc mvc) throws Exception {
        return (MockHttpSession) mvc.perform(post("/signup")
                        .param("loginId", newLoginId()).param("password", "password1").param("nickname", "테스터"))
                .andReturn().getRequest().getSession(false);
    }

    /** 실제 포트: 새 회원으로 가입한 로그인 세션. */
    static Http member(int port) throws Exception {
        Http anonymous = new Http(port, null);
        HttpResponse<String> response = anonymous.send(anonymous.request("/signup")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "loginId=" + newLoginId() + "&password=password1&nickname=tester")));
        String setCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        return new Http(port, setCookie.substring(0, setCookie.indexOf(';')));
    }

    /** 비로그인(sessionCookie == null)이거나 한 회원의 세션 쿠키를 가진 실제 HTTP 클라이언트. 요청마다 CSRF 토큰을 싣는다. */
    record Http(int port, String sessionCookie) {

        private static final HttpClient CLIENT = HttpClient.newHttpClient();

        static Http anonymous(int port) {
            return new Http(port, null);
        }

        /** WebSocket 접속에 실을 Cookie 헤더 값(비로그인이면 null). */
        String cookie() {
            return sessionCookie;
        }

        HttpRequest.Builder request(String path) {
            String cookies = "XSRF-TOKEN=" + CSRF + (sessionCookie == null ? "" : "; " + sessionCookie);
            return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Cookie", cookies)
                    .header("X-XSRF-TOKEN", CSRF);
        }

        HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(request(path).GET());
        }

        HttpResponse<String> post(String path) throws Exception {
            return send(request(path).POST(HttpRequest.BodyPublishers.noBody()));
        }

        HttpResponse<String> postJson(String path, String json) throws Exception {
            return send(request(path).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)));
        }

        HttpResponse<String> startLive(String title) throws Exception {
            return postJson("/live", "{\"title\":\"" + title + "\"}");
        }

        /** 라이브를 시작하고 성공을 확인한 뒤 id 를 돌려준다. */
        String startedLiveId(String title) throws Exception {
            HttpResponse<String> response = startLive(title);
            if (response.statusCode() != 200) {
                throw new AssertionError("라이브 시작 실패: " + response.statusCode());
            }
            return String.valueOf((Object) JsonPath.read(response.body(), "$.id"));
        }

        /** 방송 종료 안내가 보이는 시청 화면인지. */
        boolean isEnded(String liveId) throws Exception {
            return LivePages.showsEnded(get("/live/" + liveId).body());
        }
    }
}
