package kr.devcourse.chzzk.live;

import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 방송 중이면 종료로 바꾼다(조건부 갱신이라 동시에 불러도 한 번만 1을 돌려준다). */
    @Modifying(clearAutomatically = true)
    @Query("update LiveBroadcast b set b.status = kr.devcourse.chzzk.live.LiveStatus.ENDED, b.endedAt = :now "
            + "where b.id = :id and b.status = kr.devcourse.chzzk.live.LiveStatus.ON_AIR")
    int endIfOnAir(@Param("id") Long id, @Param("now") Instant now);
}
