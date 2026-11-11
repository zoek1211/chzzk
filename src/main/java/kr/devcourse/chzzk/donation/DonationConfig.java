package kr.devcourse.chzzk.donation;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 결제사 빈. 다른 {@link PaymentGateway} 구현이 없을 때, 키가 설정돼 있으면 토스 구현을, 아니면 자리표시 구현을 쓴다.
 * 토스 주소는 {@code chzzk.toss.base-url}로 바꿀 수 있다(테스트용).
 */
@Configuration
class DonationConfig {

    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    PaymentGateway paymentGateway(DonationAvailability availability,
                                  @Value("${chzzk.toss.secret-key:}") String secretKey,
                                  @Value("${chzzk.toss.base-url:" + TossPaymentGateway.DEFAULT_BASE_URL + "}")
                                  String baseUrl,
                                  @Value("${chzzk.toss.connect-timeout:5s}") Duration connectTimeout,
                                  @Value("${chzzk.toss.read-timeout:10s}") Duration readTimeout) {
        if (!availability.isEnabled()) {
            return new UnavailablePaymentGateway();
        }
        return new TossPaymentGateway(RestClient.builder().baseUrl(baseUrl)
                .requestFactory(TossPaymentGateway.requestFactory(connectTimeout, readTimeout)), secretKey);
    }
}
