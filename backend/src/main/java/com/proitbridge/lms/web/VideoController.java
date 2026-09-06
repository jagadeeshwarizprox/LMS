package com.proitbridge.lms.web;

import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.VideoAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

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

    public VideoController(VideoAccessService videos, CurrentUser current) {
        this.videos = videos;
        this.current = current;
    }

    @PostMapping("/{ref}/grant")
    public Map<String, Object> grant(@PathVariable String ref, HttpServletRequest http) {
        AuthUser me = current.get();
        return videos.grant(ref, new VideoAccessService.GrantRequest(
                me.id(), me.sessionId(), http.getHeader("X-Device-Id"), clientIp(http)));
    }

    static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : http.getRemoteAddr();
    }
}
