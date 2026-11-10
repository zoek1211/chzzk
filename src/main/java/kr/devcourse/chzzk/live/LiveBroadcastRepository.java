package kr.devcourse.chzzk.live;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveBroadcastRepository extends JpaRepository<LiveBroadcast, Long> {

    List<LiveBroadcast> findAllByStatusOrderByStartedAtDescIdDesc(LiveStatus status);
}
