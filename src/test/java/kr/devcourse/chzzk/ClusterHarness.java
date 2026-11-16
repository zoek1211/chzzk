package kr.devcourse.chzzk;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 도우미: 클러스터 모드 테스트용 Postgres·Redis 컨테이너를 JVM당 한 번만 띄우고(종료는 Testcontainers가 한다),
 * 테스트마다 {@link #newCluster(Path)} 로 격리된 공용 저장소(새 Postgres 데이터베이스 + Redis 데이터베이스 번호)를 만든다.
 * 그 위에 {@link Cluster#start(String, String...)} 로 서버 인스턴스를 몇 개든 띄운다(A, B, C ...).
 *
 * <pre>
 * &#64;Testcontainers(disabledWithoutDocker = true)
 * class MyClusterTest {
 *     &#64;TempDir Path dataDir;
 *     &#64;Test void test() throws Exception {
 *         try (var cluster = ClusterHarness.newCluster(dataDir)) {
 *             var a = cluster.start("A");
 *             var b = cluster.start("B", "--chzzk.live.broadcaster-join-timeout=1s");
 *             TestLogin.Http member = a.member();                // A에서 가입·로그인한 세션
 *             TestLogin.Http sameMember = b.withSession(member); // 같은 세션 쿠키로 B에 요청
 *         }
 *     }
 * }
 * </pre>
 *
 * 인스턴스 id는 {@code <이름>-<클러스터 번호>} 로 클러스터마다 고유하다(Redis pub/sub 채널은 데이터베이스 번호와
 * 상관없이 서버 전체가 공유하므로 id로 구분해야 한다). 컨테이너는 이 클래스를 처음 쓸 때 시작되므로
 * 테스트 클래스에는 {@code @Testcontainers(disabledWithoutDocker = true)} 를 달아 Docker 없는 환경에서는 건너뛴다.
 */
final class ClusterHarness {

    private static final AtomicInteger CLUSTERS = new AtomicInteger();
    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> redis;

    private ClusterHarness() {
    }

    private static synchronized void ensureStarted() {
        if (postgres == null) {
            PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
            GenericContainer<?> rd = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
            pg.start();
            rd.start();
            postgres = pg;
            redis = rd;
        }
    }

    /** 새 Postgres 데이터베이스와 Redis 데이터베이스 번호를 쓰는 격리된 클러스터. dataDir 은 모든 인스턴스가 공유하는 데이터 디렉터리. */
    static Cluster newCluster(Path dataDir) throws SQLException {
        ensureStarted();
        int number = CLUSTERS.incrementAndGet();
        String database = "chzzk_" + number;
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        String jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        return new Cluster(number, dataDir, jdbcUrl);
    }

    static final class Cluster implements AutoCloseable {

        private final int number;
        private final Path dataDir;
        private final String jdbcUrl;
        private final List<Instance> instances = new ArrayList<>();

        private Cluster(int number, Path dataDir, String jdbcUrl) {
            this.number = number;
            this.dataDir = dataDir;
            this.jdbcUrl = jdbcUrl;
        }

        /** 이 클러스터의 공용 저장소에 서버 인스턴스를 하나 띄운다(cluster 프로필, 임의 포트). extraArgs 는 "--name=value" 형식. */
        Instance start(String name, String... extraArgs) {
            List<String> args = new ArrayList<>(List.of(
                    "--spring.profiles.active=cluster",
                    "--server.port=0",
                    "--chzzk.data-dir=" + dataDir,
                    "--chzzk.instance-id=" + name + "-" + number,
                    "--spring.datasource.url=" + jdbcUrl,
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--spring.data.redis.host=" + redis.getHost(),
                    "--spring.data.redis.port=" + redis.getMappedPort(6379),
                    "--spring.data.redis.database=" + (number % 16)));
            args.addAll(List.of(extraArgs));
            ConfigurableApplicationContext context = new SpringApplicationBuilder(ChzzkApplication.class)
                    .run(args.toArray(String[]::new));
            Instance instance = new Instance(name, context);
            instances.add(instance);
            return instance;
        }

        @Override
        public void close() {
            for (Instance instance : instances) {
                instance.close();
            }
        }
    }

    /** 떠 있는 서버 인스턴스 하나. close() 는 그 인스턴스만 내린다(스케일 인 시뮬레이션, 여러 번 불러도 된다). */
    static final class Instance implements AutoCloseable {

        private final String name;
        private final ConfigurableApplicationContext context;

        private Instance(String name, ConfigurableApplicationContext context) {
            this.name = name;
            this.context = context;
        }

        String name() {
            return name;
        }

        ConfigurableApplicationContext context() {
            return context;
        }

        int port() {
            return ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        /** 이 인스턴스에 붙는 비로그인 HTTP 클라이언트(WebSocket Cookie 용 cookie() 도 같이). */
        TestLogin.Http anonymous() {
            return TestLogin.Http.anonymous(port());
        }

        /** 이 인스턴스에서 새 회원으로 가입·로그인한 HTTP 클라이언트. */
        TestLogin.Http member() throws Exception {
            return TestLogin.member(port());
        }

        /** 다른 인스턴스에서 얻은 로그인 세션 쿠키를 이 인스턴스에서 쓰는 클라이언트. */
        TestLogin.Http withSession(TestLogin.Http other) {
            return new TestLogin.Http(port(), other.sessionCookie());
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
