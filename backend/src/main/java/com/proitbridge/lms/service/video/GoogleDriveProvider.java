package com.proitbridge.lms.service.video;

import com.proitbridge.lms.domain.Video;
import org.springframework.stereotype.Component;

/**
 * A video file on Google Drive, played in Drive's own preview frame.
 *
 * Mentors record onboarding on Zoom and keep the file on Drive, and the publish form
 * refused the link with "upload to YouTube instead". Most never did. The file id is
 * what is stored; the preview address is built from it at grant time, like every
 * other provider, so the link itself is not in any list.
 *
 * What this does not buy: Drive decides who may open the file, not this application.
 * The file has to be shared with the learners (anyone with the link, or the batch's
 * accounts), or the frame shows Drive's own access request. The entitlement check
 * here still runs first, so a learner outside the batch never sees the frame at all.
 */
@Component
public class GoogleDriveProvider implements VideoProvider {

    @Override
    public String key() { return "GOOGLE_DRIVE"; }

    @Override
    public Grant grant(Video video, String viewerFingerprint) {
        return new Grant("GOOGLE_DRIVE", video.getExternalId(),
                "https://drive.google.com/file/d/" + video.getExternalId() + "/preview", 120, false);
    }
}
