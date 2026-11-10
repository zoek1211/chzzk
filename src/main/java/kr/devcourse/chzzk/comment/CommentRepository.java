package kr.devcourse.chzzk.comment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query("""
            select c from Comment c join fetch c.author
            where c.kind = :kind and c.broadcastId = :broadcastId
            order by c.createdAt asc, c.id asc
            """)
    List<Comment> findOldestFirst(BroadcastKind kind, Long broadcastId);
}
