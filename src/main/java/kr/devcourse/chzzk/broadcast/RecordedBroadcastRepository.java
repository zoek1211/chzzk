package kr.devcourse.chzzk.broadcast;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordedBroadcastRepository extends JpaRepository<RecordedBroadcast, Long> {

    List<RecordedBroadcast> findAllByOrderByRegisteredAtDescIdDesc();
}
