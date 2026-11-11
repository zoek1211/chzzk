package kr.devcourse.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 토스 API 호출의 URL·헤더·본문과 응답·오류 파싱을 MockRestServiceServer로 확인한다. */
class TossPaymentGatewayTest {

    static final String BASIC =
            "Basic " + Base64.getEncoder().encodeToString("test_sk_x:".getBytes(StandardCharsets.UTF_8));

    MockRestServiceServer server;
    TossPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(TossPaymentGateway.DEFAULT_BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new TossPaymentGateway(builder, "test_sk_x");
    }

    @Test
    void 승인은_confirm_주소로_본문과_Basic_인증을_보내고_결과를_읽는다() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(jsonPath("$.paymentKey").value("pk_1"))
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.amount").value(5000))
                .andRespond(withSuccess("""
                        {"status":"DONE","totalAmount":5000,"orderId":"order-1","paymentKey":"pk_1","method":"카드"}
                        """, MediaType.APPLICATION_JSON));

        PaymentResult result = gateway.confirm("pk_1", "order-1", 5000);

        assertThat(result).isEqualTo(new PaymentResult("DONE", 5000, "order-1", "pk_1"));
        assertThat(result.isDone()).isTrue();
        server.verify();
    }

    @Test
    void 조회는_paymentKey_경로로_GET한다() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pk_2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", BASIC))
                .andRespond(withSuccess("""
                        {"status":"CANCELED","totalAmount":1000,"orderId":"order-2","paymentKey":"pk_2"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.query("pk_2")).isEqualTo(new PaymentResult("CANCELED", 1000, "order-2", "pk_2"));
        server.verify();
    }

    @Test
    void 취소는_cancel_주소로_cancelReason을_보낸다() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pk_3/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(jsonPath("$.cancelReason").value("방송 종료 후 환불"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        gateway.cancel("pk_3", "방송 종료 후 환불");

        server.verify();
    }

    @Test
    void 오류_응답의_code와_message를_예외로_옮긴다() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}"));

        assertThatThrownBy(() -> gateway.confirm("pk_1", "order-1", 5000))
                .isInstanceOfSatisfying(PaymentGatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo("ALREADY_PROCESSED_PAYMENT");
                    assertThat(e.getMessage()).isEqualTo("이미 처리된 결제 입니다.");
                });
    }

    @Test
    void JSON이_아닌_오류_응답은_코드_없는_예외가_된다() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pk_1")).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.query("pk_1"))
                .isInstanceOfSatisfying(PaymentGatewayException.class, e -> assertThat(e.code()).isNull());
    }

    @Test
    void 네트워크_오류는_NETWORK_ERROR_예외가_된다() {
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/pk_1"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThatThrownBy(() -> gateway.query("pk_1"))
                .isInstanceOfSatisfying(PaymentGatewayException.class,
                        e -> assertThat(e.code()).isEqualTo("NETWORK_ERROR"));
    }

    @Test
    void 응답이_읽기_제한_시간을_넘기면_NETWORK_ERROR_예외가_된다() throws IOException {
        HttpServer slow = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(1500);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException | IOException ignored) {
                // 클라이언트가 먼저 끊었다
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + slow.getAddress().getPort())
                    .requestFactory(TossPaymentGateway.requestFactory(Duration.ofSeconds(1), Duration.ofMillis(200)));
            TossPaymentGateway timed = new TossPaymentGateway(builder, "test_sk_x");

            assertThatThrownBy(() -> timed.query("pk_1"))
                    .isInstanceOfSatisfying(PaymentGatewayException.class,
                            e -> assertThat(e.code()).isEqualTo("NETWORK_ERROR"));
        } finally {
            slow.stop(0);
        }
    }
}
