package kr.devcourse.chzzk.live;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 서비스가 시작될 때 이전 실행에서 남은 방송 중 라이브 방송을 모두 종료로 바꾼다.
 * 모든 빈이 만들어진 직후, 웹 서버가 요청을 받기 시작하기 전에 끝난다. 2단계 데이터 파일의 옛 송출 키 해시 열도 이때 지운다.
 */
@Component
class LiveStartupCleanup implements SmartInitializingSingleton {

    private final LiveBroadcastService live;
    private final JdbcTemplate jdbc;

    LiveStartupCleanup(LiveBroadcastService live, JdbcTemplate jdbc) {
        this.live = live;
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 2단계 데이터 파일에 남은 송출 키 해시 열(NOT NULL)은 ddl-auto=update 가 지우지 않아 새 라이브 저장을 막는다.
        jdbc.execute("ALTER TABLE live_broadcast DROP COLUMN IF EXISTS broadcast_key_hash");
        live.endAllOnAir();
    }
}
