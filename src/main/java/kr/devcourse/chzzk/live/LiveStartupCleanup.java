package kr.devcourse.chzzk.live;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 서비스가 시작될 때 이전 실행에서 남은 방송 중 라이브 방송을 모두 종료로 바꾼다.
 * 모든 빈이 만들어진 직후, 웹 서버가 요청을 받기 시작하기 전에 끝난다.
 */
@Component
class LiveStartupCleanup implements SmartInitializingSingleton {

    private final LiveBroadcastService live;

    LiveStartupCleanup(LiveBroadcastService live) {
        this.live = live;
    }

    @Override
    public void afterSingletonsInstantiated() {
        live.endAllOnAir();
    }
}
