package kr.devcourse.chzzk.video;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** 업로드 영상을 로컬 디스크에 보관하고 다시 꺼낸다. */
@Component
public class VideoStorage {

    private final Path directory;

    public VideoStorage(@Value("${chzzk.storage.video-dir}") Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("영상 보관 디렉터리를 만들 수 없습니다: " + this.directory, e);
        }
    }

    /** 영상을 보관하고, 나중에 꺼낼 때 쓸 파일 이름을 돌려준다. */
    public String store(InputStream content, String originalFileName) {
        String storedName = UUID.randomUUID() + extensionOf(originalFileName);
        try (content) {
            Files.copy(content, directory.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("영상을 저장하지 못했습니다", e);
        }
        return storedName;
    }

    public Optional<Resource> load(String storedName) {
        Path file = directory.resolve(storedName).normalize();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemResource(file));
    }

    /** 보관된 영상을 지운다. 이미 없으면 아무 일도 하지 않는다. */
    public void delete(String storedName) {
        Path file = directory.resolve(storedName).normalize();
        if (!file.getParent().equals(directory)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("영상을 삭제하지 못했습니다", e);
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? "." + ext : "";
    }
}
