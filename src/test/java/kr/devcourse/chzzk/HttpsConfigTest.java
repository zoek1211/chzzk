package kr.devcourse.chzzk;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HttpsConfigTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @Autowired
    Environment env;

    @Test
    void 환경변수가_없으면_HTTPS는_꺼진_HTTP_기본이다() {
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.ssl.key-store")).isNullOrEmpty();
        assertThat(env.getProperty("server.ssl.key-store-password")).isNullOrEmpty();
    }
}
