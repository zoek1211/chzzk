package kr.devcourse.chzzk.donation;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 토스페이먼츠 API 구현. Basic 인증은 {@code base64(secretKey + ":")}. */
public class TossPaymentGateway implements PaymentGateway {

    public static final String DEFAULT_BASE_URL = "https://api.tosspayments.com";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;

    /** @param builder 기본 주소를 정한 빌더(테스트는 MockRestServiceServer를 묶는다) */
    public TossPaymentGateway(RestClient.Builder builder, String secretKey) {
        String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.client = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials).build();
    }

    @Override
    public PaymentResult confirm(String paymentKey, String orderId, long amount) {
        return toResult(call(client.post().uri("/v1/payments/confirm").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))));
    }

    @Override
    public PaymentResult query(String paymentKey) {
        return toResult(call(client.get().uri("/v1/payments/{paymentKey}", paymentKey)));
    }

    @Override
    public void cancel(String paymentKey, String reason) {
        call(client.post().uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("cancelReason", reason)));
    }

    private JsonNode call(RestClient.RequestHeadersSpec<?> request) {
        String text;
        try {
            text = request.retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw toException(e);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("NETWORK_ERROR", "결제사에 연결하지 못했습니다.");
        }
        try {
            return JSON.readTree(text == null || text.isBlank() ? "{}" : text);
        } catch (RuntimeException e) {
            throw new PaymentGatewayException("INVALID_RESPONSE", "결제사 응답을 읽지 못했습니다.");
        }
    }

    private static PaymentGatewayException toException(RestClientResponseException e) {
        try {
            JsonNode error = JSON.readTree(e.getResponseBodyAsString());
            String code = error.path("code").asString(null);
            if (code != null) {
                return new PaymentGatewayException(code, error.path("message").asString("결제사 오류"));
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아니면 아래 기본 예외
        }
        return new PaymentGatewayException(null, "결제사 오류 응답: HTTP " + e.getStatusCode().value());
    }

    private static PaymentResult toResult(JsonNode node) {
        return new PaymentResult(node.path("status").asString(null), node.path("totalAmount").asLong(),
                node.path("orderId").asString(null), node.path("paymentKey").asString(null));
    }
}
