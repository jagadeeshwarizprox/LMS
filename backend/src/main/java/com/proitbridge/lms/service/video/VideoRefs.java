package com.proitbridge.lms.service.video;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turning whatever someone pasted into a video id.
 *
 * Nobody copies an id. They copy the address bar, or the Share button, or the embed
 * snippet, and every one of those is a different shape. Asking for "the id" and rejecting
 * the rest would be a support ticket a week, so this accepts all of them and stores the
 * one thing that matters.
 */
public final class VideoRefs {

    private VideoRefs() {}

    /** youtu.be/ID, watch?v=ID, /embed/ID, /shorts/ID, /live/ID, or a bare id. */
    private static final List<Pattern> YOUTUBE = List.of(
            Pattern.compile("(?:youtube\\.com|youtube-nocookie\\.com)/watch\\?(?:.*&)?v=([\\w-]{11})"),
            Pattern.compile("youtu\\.be/([\\w-]{11})"),
            Pattern.compile("(?:youtube\\.com|youtube-nocookie\\.com)/embed/([\\w-]{11})"),
            Pattern.compile("youtube\\.com/shorts/([\\w-]{11})"),
            Pattern.compile("youtube\\.com/live/([\\w-]{11})"),
            Pattern.compile("^([\\w-]{11})$")
    );

    /** Cloudflare Stream uses a 32 character hex uid, either bare or in a URL. */
    private static final List<Pattern> CLOUDFLARE = List.of(
            Pattern.compile("cloudflarestream\\.com/([0-9a-f]{32})"),
            Pattern.compile("videodelivery\\.net/([0-9a-f]{32})"),
            Pattern.compile("^([0-9a-f]{32})$")
    );

    public static String parse(String input, String provider) {
        if (input == null || input.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste the video link or id.");
        }
        String raw = input.trim();

        // an embed snippet was pasted whole; the src inside it is the part that matters
        Matcher iframe = Pattern.compile("src=[\"']([^\"']+)[\"']").matcher(raw);
        if (iframe.find()) raw = iframe.group(1);

        List<Pattern> patterns = "CLOUDFLARE_STREAM".equals(provider) ? CLOUDFLARE : YOUTUBE;
        for (Pattern p : patterns) {
            Matcher m = p.matcher(raw);
            if (m.find()) return m.group(1);
        }

        if ("CLOUDFLARE_STREAM".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is not a Cloudflare Stream video. Paste the link from the dashboard, "
                    + "or the 32 character uid.");
        }
        if (raw.contains("youtube.com/playlist") || raw.contains("list=")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is a playlist, not a video. Open the video itself and copy its link.");
        }
        if (raw.contains("drive.google.com") || raw.contains("vimeo.com")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only YouTube and Cloudflare Stream play here. Upload to YouTube as unlisted "
                    + "and paste that link.");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "That does not look like a YouTube video. Paste the link from the address bar "
                + "or the Share button.");
    }
}
