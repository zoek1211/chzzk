package kr.devcourse.chzzk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/** 테스트 도우미: 새 회원을 가입시키고 그 로그인 세션을 돌려준다(가입하면 바로 로그인된다). */
final class MemberSessions {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private MemberSessions() {
    }

    static MockHttpSession signUpAs(MockMvc mvc, String nickname) throws Exception {
        String loginId = "member" + SEQ.incrementAndGet() + "x";
        return (MockHttpSession) mvc.perform(post("/signup")
                        .param("loginId", loginId).param("password", "password1").param("nickname", nickname))
                .andReturn().getRequest().getSession(false);
    }
}
