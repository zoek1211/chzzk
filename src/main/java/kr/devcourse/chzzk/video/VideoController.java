package kr.devcourse.chzzk.video;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업로드 영상을 플레이어에 내려준다. Resource 를 그대로 돌려주면 Spring 이 Range 요청(구간 재생·탐색)을 처리한다.
 */
@RestController
public class VideoController {

    private final VideoStorage storage;

    public VideoController(VideoStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/videos/{storedName}")
    public ResponseEntity<Resource> stream(@PathVariable String storedName) {
        return storage.load(storedName)
                .map(video -> ResponseEntity.ok()
                        .contentType(MediaTypeFactory.getMediaType(video)
                                .orElse(MediaType.APPLICATION_OCTET_STREAM))
                        .body(video))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
