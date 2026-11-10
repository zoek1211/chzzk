package kr.devcourse.chzzk.broadcast;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;

/** 녹화방송: 녹화된 영상과 제목. 영상 출처는 업로드 영상 또는 외부 영상 중 하나다. */
@Entity
public class RecordedBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoSource videoSource;

    /** 업로드 영상이면 보관된 파일 이름, 외부 영상이면 영상 주소. */
    @Column(nullable = false, length = 2000)
    private String videoLocation;

    @Column(nullable = false)
    private Instant registeredAt;

    protected RecordedBroadcast() {
    }

    private RecordedBroadcast(String title, VideoSource videoSource, String videoLocation) {
        this.title = title;
        this.videoSource = videoSource;
        this.videoLocation = videoLocation;
        this.registeredAt = Instant.now();
    }

    public static RecordedBroadcast withExternalVideo(String title, String videoUrl) {
        return new RecordedBroadcast(validTitle(title), VideoSource.EXTERNAL, validVideoUrl(videoUrl));
    }

    public static RecordedBroadcast withUploadedVideo(String title, String storedFileName) {
        return new RecordedBroadcast(validTitle(title), VideoSource.UPLOADED, storedFileName);
    }

    /** 제목 규칙: 공백만으로 이루어질 수 없고 최대 200자. 앞뒤 공백은 지운다. */
    public static String validTitle(String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidRegistrationException("제목을 입력해 주세요.");
        }
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new InvalidRegistrationException("제목은 " + MAX_TITLE_LENGTH + "자 이하로 입력해 주세요.");
        }
        return trimmed;
    }

    /** 외부 영상 주소 규칙: http 또는 https 절대 주소. */
    public static String validVideoUrl(String videoUrl) {
        String trimmed = videoUrl == null ? "" : videoUrl.strip();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && trimmed.length() <= MAX_URL_LENGTH) {
                return trimmed;
            }
        } catch (URISyntaxException ignored) {
            // 아래에서 같은 오류로 처리
        }
        throw new InvalidRegistrationException("영상 주소는 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다.");
    }

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_URL_LENGTH = 2000;

    /** 플레이어가 영상을 가져올 주소. */
    public String playbackUrl() {
        return switch (videoSource) {
            case EXTERNAL -> videoLocation;
            case UPLOADED -> "/videos/" + videoLocation;
        };
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public VideoSource getVideoSource() {
        return videoSource;
    }

    public String getVideoLocation() {
        return videoLocation;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
