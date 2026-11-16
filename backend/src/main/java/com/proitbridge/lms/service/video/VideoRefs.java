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

    /*
     * Google Drive. A file link comes as /file/d/ID/view, /file/d/ID/preview, open?id=ID
     * or uc?id=ID depending on which button was pressed; a folder as /drive/folders/ID,
     * sometimes with /u/0/ in the middle. Drive ids are long and mixed case, so the
     * pattern asks for at least twenty of the characters they use rather than a length.
     */
    private static final List<Pattern> DRIVE_FILE = List.of(
            Pattern.compile("drive\\.google\\.com/file/d/([\\w-]{20,})"),
            Pattern.compile("drive\\.google\\.com/(?:open|uc)\\?(?:.*&)?id=([\\w-]{20,})"),
            Pattern.compile("docs\\.google\\.com/file/d/([\\w-]{20,})")
    );
    private static final List<Pattern> DRIVE_FOLDER = List.of(
            Pattern.compile("drive\\.google\\.com/(?:drive/)?(?:u/\\d+/)?folders/([\\w-]{20,})")
    );

    /**
     * Which provider a pasted link belongs to, when the form did not say.
     *
     * The publish form takes one box for "the link", and a mentor pastes whatever they
     * have. Drive links were refused outright with instructions to upload to YouTube,
     * which is how onboarding recordings never reached the batch they were made for.
     * Drive is now read for what it is; anything that is not Drive or Cloudflare is
     * handed to the YouTube parser, which still explains the shapes it does not take.
     */
    public static String detectProvider(String input, String fallback) {
        if (input == null) return fallback;
        String raw = input.trim();
        for (Pattern p : DRIVE_FOLDER) if (p.matcher(raw).find()) return "GOOGLE_DRIVE_FOLDER";
        for (Pattern p : DRIVE_FILE) if (p.matcher(raw).find()) return "GOOGLE_DRIVE";
        if (raw.contains("cloudflarestream.com") || raw.contains("videodelivery.net")) return "CLOUDFLARE_STREAM";
        return fallback == null ? "YOUTUBE" : fallback;
    }

    public static String parse(String input, String provider) {
        if (input == null || input.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste the video link or id.");
        }
        String raw = input.trim();

        // an embed snippet was pasted whole; the src inside it is the part that matters
        Matcher iframe = Pattern.compile("src=[\"']([^\"']+)[\"']").matcher(raw);
        if (iframe.find()) raw = iframe.group(1);

        List<Pattern> patterns = switch (provider == null ? "YOUTUBE" : provider) {
            case "CLOUDFLARE_STREAM" -> CLOUDFLARE;
            case "GOOGLE_DRIVE" -> DRIVE_FILE;
            case "GOOGLE_DRIVE_FOLDER" -> DRIVE_FOLDER;
            default -> YOUTUBE;
        };
        for (Pattern p : patterns) {
            Matcher m = p.matcher(raw);
            if (m.find()) return m.group(1);
        }

        if ("CLOUDFLARE_STREAM".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is not a Cloudflare Stream video. Paste the link from the dashboard, "
                    + "or the 32 character uid.");
        }
        if ("GOOGLE_DRIVE".equals(provider) || "GOOGLE_DRIVE_FOLDER".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That Drive link has no file or folder id in it. Use Share, copy the link, "
                    + "and paste the whole thing.");
        }
        if (raw.contains("youtube.com/playlist") || raw.contains("list=")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is a playlist, not a video. Open the video itself and copy its link.");
        }
        if (raw.contains("drive.google.com")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That Drive link has no file or folder id in it. Use Share, copy the link, "
                    + "and paste the whole thing.");
        }
        if (raw.contains("vimeo.com")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "YouTube, Google Drive and Cloudflare Stream play here. Vimeo does not.");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "That does not look like a YouTube video. Paste the link from the address bar "
                + "or the Share button.");
    }
}
