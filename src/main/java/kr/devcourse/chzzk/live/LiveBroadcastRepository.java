package kr.devcourse.chzzk.live;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LiveBroadcastRepository extends JpaRepository<LiveBroadcast, Long> {

    /** 방송 목록 순서: 방송 중이 먼저, 그다음 종료. 각 그룹은 최신순. */
    @Query("""
            select b from LiveBroadcast b
            order by case when b.status = kr.devcourse.chzzk.live.LiveStatus.ON_AIR then 0 else 1 end,
                     b.startedAt desc, b.id desc
            """)
    List<LiveBroadcast> findAllInListOrder();

    List<LiveBroadcast> findAllByStatus(LiveStatus status);

    boolean existsByOwnerIdAndStatus(Long ownerId, LiveStatus status);
}
