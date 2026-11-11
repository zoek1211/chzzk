package kr.devcourse.chzzk.donation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 결제사 빈. 다른 {@link PaymentGateway} 구현이 등록되지 않았을 때만 자리표시 구현을 쓴다. */
@Configuration
class DonationConfig {

    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    PaymentGateway unavailablePaymentGateway() {
        return new UnavailablePaymentGateway();
    }
}
