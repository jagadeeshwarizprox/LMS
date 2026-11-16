package com.proitbridge.lms.service.video;

import com.proitbridge.lms.domain.Video;
import org.springframework.stereotype.Component;

/**
 * A Google Drive folder of recordings, opened in Drive rather than played here.
 *
 * An onboarding often leaves several files: the call, the screen share, the slides.
 * Rather than publishing each one, a mentor shares the folder. A folder cannot be
 * embedded as a video, so the grant carries the folder address and the player opens
 * it in a new tab. The same entitlement check gates it, so the address is only ever
 * handed to a learner in the batch it was published to.
 */
@Component
public class GoogleDriveFolderProvider implements VideoProvider {

    @Override
    public String key() { return "GOOGLE_DRIVE_FOLDER"; }

    @Override
    public Grant grant(Video video, String viewerFingerprint) {
        return new Grant("GOOGLE_DRIVE_FOLDER", video.getExternalId(),
                "https://drive.google.com/drive/folders/" + video.getExternalId(), 120, false);
    }
}
