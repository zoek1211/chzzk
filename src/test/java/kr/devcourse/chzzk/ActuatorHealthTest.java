package kr.devcourse.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 단독 모드(Redis·Postgres 없음)에서 /actuator/health 만 비로그인으로 열린다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("chzzk.data-dir", dataDir::toString);
    }

    @LocalServerPort
    int port;

    @Test
    void health는_비로그인으로_200이고_liveness_readiness_그룹도_열린다() throws Exception {
        TestLogin.Http anonymous = TestLogin.Http.anonymous(port);

        assertThat(anonymous.get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(anonymous.get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void health_말고_다른_actuator_엔드포인트는_노출되지_않는다() throws Exception {
        TestLogin.Http anonymous = TestLogin.Http.anonymous(port);

        assertThat(anonymous.get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(anonymous.get("/actuator/beans").statusCode()).isEqualTo(404);
        assertThat(anonymous.get("/actuator/info").statusCode()).isEqualTo(404);
    }

    @Test
    void 단독_모드에서는_인스턴스_이름_헤더를_붙이지_않는다() throws Exception {
        TestLogin.Http anonymous = TestLogin.Http.anonymous(port);

        assertThat(anonymous.get("/broadcasts").headers().firstValue("X-Chzzk-Instance")).isEmpty();
    }
}
