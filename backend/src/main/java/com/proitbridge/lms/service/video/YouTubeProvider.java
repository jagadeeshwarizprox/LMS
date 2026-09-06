package com.proitbridge.lms.service.video;

import com.proitbridge.lms.domain.Video;
import org.springframework.stereotype.Component;

/**
 * Unlisted YouTube, played in YouTube's own player.
 *
 * Be clear about what this does and does not buy. The id is never printed, never
 * in the bundle, never in a list response, and is handed over one video at a time
 * only after entitlement is re-checked. That removes every casual path to it.
 * It does not survive devtools: the iframe must carry the id for the embed to
 * work at all. That is why Video.externalId is rotatable, so a leak is a chore
 * rather than permanent damage, and why the watermark carries the viewer's name.
 */
@Component
public class YouTubeProvider implements VideoProvider {

    @Override
    public String key() { return "YOUTUBE"; }

    @Override
    public Grant grant(Video video, String viewerFingerprint) {
        return new Grant("YOUTUBE", video.getExternalId(), null, 120, false);
    }
}
