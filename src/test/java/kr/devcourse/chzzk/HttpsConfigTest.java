package kr.devcourse.chzzk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HttpsConfigTest {

    @Autowired
    Environment env;

    @Test
    void 환경변수가_없으면_HTTPS는_꺼진_HTTP_기본이다() {
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.ssl.key-store")).isNullOrEmpty();
        assertThat(env.getProperty("server.ssl.key-store-password")).isNullOrEmpty();
    }
}
