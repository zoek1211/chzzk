package kr.devcourse.chzzk.donation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 토스 키가 둘 다 설정돼 있을 때만 후원을 받을 수 있다. 비밀 키는 여기서 밖으로 내지 않는다. */
@Component("donationAvailability")
public class DonationAvailability {

    private final String clientKey;
    private final boolean enabled;

    DonationAvailability(@Value("${chzzk.toss.client-key:}") String clientKey,
                         @Value("${chzzk.toss.secret-key:}") String secretKey) {
        this.clientKey = clientKey == null ? "" : clientKey.trim();
        this.enabled = !this.clientKey.isEmpty() && secretKey != null && !secretKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 결제창을 여는 클라이언트 키(공개 값). 비활성이면 빈 문자열. */
    public String clientKey() {
        return enabled ? clientKey : "";
    }
}
