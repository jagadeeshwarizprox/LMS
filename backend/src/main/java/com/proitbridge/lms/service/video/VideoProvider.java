package com.proitbridge.lms.service.video;

import com.proitbridge.lms.domain.Video;

/**
 * How a video is played. The rest of the application never learns a provider
 * identifier: it asks for a grant and gets back whatever the player needs, valid
 * for a short window. Swapping provider is a config change, not a rewrite.
 */
public interface VideoProvider {

    String key();

    /** Mints whatever the client needs to play this video, now, for this viewer. */
    Grant grant(Video video, String viewerFingerprint);

    /**
     * What the client receives. For YouTube this carries the id, because the embed
     * cannot work without it. For a signed provider it carries a token that stops
     * working when it expires and refuses to play off our domain.
     */
    record Grant(String provider, String playbackId, String playbackUrl, long ttlSeconds, boolean revocable) {}
}
