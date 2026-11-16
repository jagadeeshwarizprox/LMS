package com.proitbridge.lms.service.video;

import com.proitbridge.lms.domain.Video;
import org.springframework.stereotype.Component;

/**
 * A recording uploaded straight into the LMS.
 *
 * RecordingService has written videos with provider UPLOAD since uploads were added,
 * and nothing was registered under that key, so every one of them answered "No player
 * is configured for this video" the first time a learner pressed play. The file was
 * stored, the recording was listed, and it could not be watched.
 *
 * The id handed back is the stored file. VideoAccessService turns the grant it logs
 * into the stream address, so the address a player receives is good for one viewer,
 * for a window long enough to watch the whole thing, and stops working after that.
 * That is the same promise the signed providers make, kept with the access log rather
 * than a key pair.
 */
@Component
public class UploadProvider implements VideoProvider {

    /** Long enough to watch and seek through a two hour session, then it is dead. */
    static final long TTL_SECONDS = 3 * 60 * 60;

    @Override
    public String key() { return "UPLOAD"; }

    @Override
    public Grant grant(Video video, String viewerFingerprint) {
        return new Grant("UPLOAD", video.getExternalId(), null, TTL_SECONDS, true);
    }
}
