package kr.devcourse.chzzk.broadcast;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import kr.devcourse.chzzk.member.Member;
import kr.devcourse.chzzk.video.VideoStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 녹화방송 모듈의 공개 인터페이스: 목록·조회·등록·수정·삭제. 수정·삭제는 소유자만 할 수 있다. */
@Service
public class RecordedBroadcastService {

    /** 업로드한 영상 파일. */
    public record Upload(InputStream content, String originalFilename) {
    }

    private final RecordedBroadcastRepository broadcasts;
    private final VideoStorage videoStorage;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    public RecordedBroadcastService(RecordedBroadcastRepository broadcasts, VideoStorage videoStorage,
                                    EntityManager entityManager, ApplicationEventPublisher events) {
        this.broadcasts = broadcasts;
        this.videoStorage = videoStorage;
        this.entityManager = entityManager;
        this.events = events;
    }

    /** 최근 등록한 것이 먼저. */
    @Transactional(readOnly = true)
    public List<RecordedBroadcast> list() {
        return broadcasts.findAllByOrderByRegisteredAtDescIdDesc();
    }

    /** @throws RecordedBroadcastNotFoundException 없는 녹화방송 */
    @Transactional(readOnly = true)
    public RecordedBroadcast find(Long id) {
        return broadcasts.findById(id).orElseThrow(RecordedBroadcastNotFoundException::new);
    }

    /**
     * 이 녹화방송의 소유자 회원 id. 소유자 없는 방송(회원 기능 이전 데이터)이면 비어 있다.
     * 후원처럼 다른 모듈이 "누가 소유자인가"를 물을 때 쓴다.
     *
     * @throws RecordedBroadcastNotFoundException 없는 녹화방송
     */
    @Transactional(readOnly = true)
    public Optional<Long> ownerIdOf(Long id) {
        Member owner = find(id).getOwner();
        return owner == null ? Optional.empty() : Optional.of(owner.getId());
    }

    /**
     * 회원이 녹화방송을 등록한다. 영상은 업로드 파일과 외부 주소 중 정확히 하나여야 한다.
     *
     * @param upload 업로드 영상(없으면 null)
     * @param videoUrl 외부 영상 주소(없으면 null이나 빈 값)
     * @throws InvalidRegistrationException 제목·영상 규칙 위반
     */
    public RecordedBroadcast register(Long memberId, String title, String videoUrl, Upload upload)
            throws IOException {
        boolean hasFile = upload != null;
        boolean hasUrl = videoUrl != null && !videoUrl.isBlank();
        if (hasFile == hasUrl) {
            throw new InvalidRegistrationException(hasFile
                    ? "영상 파일과 영상 주소 중 하나만 입력해 주세요."
                    : "영상 파일을 올리거나 영상 주소를 입력해 주세요.");
        }

        RecordedBroadcast broadcast;
        if (hasFile) {
            // 파일을 보관하기 전에 제목부터 검사해 버려질 파일이 남지 않게 한다.
            String validTitle = RecordedBroadcast.validTitle(title);
            String storedName = videoStorage.store(upload.content(), upload.originalFilename());
            broadcast = RecordedBroadcast.withUploadedVideo(validTitle, storedName);
        } else {
            broadcast = RecordedBroadcast.withExternalVideo(title, videoUrl);
        }
        broadcast.assignOwner(entityManager.getReference(Member.class, memberId));
        return broadcasts.save(broadcast);
    }

    /**
     * 소유자가 제목을 고친다.
     *
     * @throws RecordedBroadcastNotFoundException 없는 녹화방송
     * @throws NotRecordedBroadcastOwnerException 소유자가 아님(소유자 없는 방송 포함)
     * @throws InvalidRegistrationException 제목 규칙 위반
     */
    @Transactional
    public void rename(Long id, Long memberId, String title) {
        findOwned(id, memberId).rename(title);
    }

    /**
     * 소유자가 녹화방송을 삭제한다. 같은 트랜잭션에서 삭제 이벤트를 내고(댓글 정리), 업로드 영상 파일도 지운다.
     *
     * @throws RecordedBroadcastNotFoundException 없는 녹화방송
     * @throws NotRecordedBroadcastOwnerException 소유자가 아님(소유자 없는 방송 포함)
     */
    @Transactional
    public void delete(Long id, Long memberId) {
        RecordedBroadcast broadcast = findOwned(id, memberId);
        broadcasts.delete(broadcast);
        events.publishEvent(new RecordedBroadcastDeleted(id));
        if (broadcast.getVideoSource() == VideoSource.UPLOADED) {
            videoStorage.delete(broadcast.getVideoLocation());
        }
    }

    /** 소유자 확인을 거쳐 녹화방송을 찾는다. 수정 폼을 보여 줄 때도 쓴다. */
    @Transactional(readOnly = true)
    public RecordedBroadcast findOwned(Long id, Long memberId) {
        RecordedBroadcast broadcast = find(id);
        if (!broadcast.isOwnedBy(memberId)) {
            throw new NotRecordedBroadcastOwnerException();
        }
        return broadcast;
    }
}
