package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.StoredFile;
import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.FileService;
import com.proitbridge.lms.service.VideoAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

/**
 * The single door to a playable video. One at a time, at the moment of play,
 * after the entitlement is re-checked, and always logged.
 */
@RestController
@RequestMapping("/api/video")
public class VideoController {

    private final VideoAccessService videos;
    private final CurrentUser current;
    private final FileService files;

    public VideoController(VideoAccessService videos, CurrentUser current, FileService files) {
        this.videos = videos;
        this.current = current;
        this.files = files;
    }

    @PostMapping("/{ref}/grant")
    public Map<String, Object> grant(@PathVariable String ref, HttpServletRequest http) {
        AuthUser me = current.get();
        return videos.grant(ref, new VideoAccessService.GrantRequest(
                me.id(), me.sessionId(), http.getHeader("X-Device-Id"), clientIp(http)));
    }

    /**
     * The bytes of an uploaded recording, for the player, by grant.
     *
     * This path carries no session, because a <video> element sends none; the grant id
     * in the path is the ticket, checked on every request. Returning the file as a
     * Resource lets the framework answer Range requests with 206, which is what makes
     * seeking work and keeps a two hour file from ever being read into memory whole.
     * Disposition is inline, not attachment: this is playback, not a download.
     */
    @GetMapping("/stream/{grantId}")
    public ResponseEntity<Resource> stream(@PathVariable String grantId) {
        String fileId = videos.streamFileFor(grantId);
        StoredFile f = files.meta(fileId);
        Path onDisk = files.localPath(fileId);
        Resource body = onDisk != null ? new FileSystemResource(onDisk)
                : new ByteArrayResource(files.read(fileId));
        String type = f.getContentType() == null || f.getContentType().isBlank()
                || !f.getContentType().startsWith("video/")
                ? videoTypeFor(f.getFilename()) : f.getContentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(type))
                .body(body);
    }

    /** The browser's MIME for a recording is not reliable; the extension is what was chosen. */
    private static String videoTypeFor(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        return "video/mp4";
    }

    static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : http.getRemoteAddr();
    }
}
