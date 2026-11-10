package kr.devcourse.chzzk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.http.HttpRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 테스트 도우미: 실제 보안 필터 체인을 거치는 MockMvc와, CSRF 토큰을 실어 보내는 HttpClient 요청. */
final class TestWeb {

    private static final String CSRF_TOKEN = "test-csrf-token";

    private TestWeb() {
    }

    /** 보안 필터 체인을 거치고, 모든 요청에 올바른 CSRF 토큰을 자동으로 싣는다. */
    static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(csrf()))
                .build();
    }

    /** 보안 필터 체인은 거치지만 CSRF 토큰은 싣지 않는다(거절 확인용). */
    static MockMvc mvcWithoutCsrf(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** 실제 HTTP 요청(HttpClient)에 CSRF 쿠키와 헤더를 싣는다. 쿠키 방식 저장소라 두 값이 같으면 통과한다. */
    static HttpRequest.Builder withCsrf(HttpRequest.Builder request) {
        return request.header("Cookie", "XSRF-TOKEN=" + CSRF_TOKEN).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    /** 화면마다 달라지는 CSRF 메타 태그를 지운 HTML. 화면 내용 비교용. */
    static String withoutCsrfMeta(String html) {
        return html.replaceAll("<meta name=\"_csrf[^>]*>", "");
    }
}
