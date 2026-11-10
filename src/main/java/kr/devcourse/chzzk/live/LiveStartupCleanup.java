package kr.devcourse.chzzk.live;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 서비스가 시작될 때 이전 실행에서 남은 방송 중 라이브 방송을 모두 종료로 바꾼다. */
@Component
class LiveStartupCleanup implements ApplicationRunner {

    private final LiveBroadcastService live;

    LiveStartupCleanup(LiveBroadcastService live) {
        this.live = live;
    }

    @Override
    public void run(ApplicationArguments args) {
        live.endAllOnAir();
    }
}
