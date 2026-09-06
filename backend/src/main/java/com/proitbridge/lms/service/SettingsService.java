package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.AppSetting;
import com.proitbridge.lms.domain.GatePolicy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.proitbridge.lms.domain.Video;
import com.proitbridge.lms.repo.AppSettingRepository;
import com.proitbridge.lms.repo.VideoRepository;
import com.proitbridge.lms.service.video.VideoRefs;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The settings that were environment variables or literals.
 *
 * An environment variable is the wrong home for anything the team needs to change on a
 * Tuesday afternoon: it means a restart, and usually someone with server access. These
 * live in the database, are edited in the product, and fall back to a sensible default
 * when nobody has set them.
 */
@Service
public class SettingsService {

    private final AppSettingRepository settings;
    private final VideoRepository videos;
    private final ActivityService activity;

    public SettingsService(AppSettingRepository settings, VideoRepository videos,
                           ActivityService activity) {
        this.settings = settings;
        this.videos = videos;
        this.activity = activity;
    }

    /** Everything editable, with what it means, so the screen needs no hard-coded list. */
    public static final List<Map<String, String>> DEFINITIONS = List.of(
        Map.of("key", "org.name", "label", "Organisation name", "type", "text",
               "group", "Brand", "default", "ProITBridge",
               "note", "Shown in mail and on the sign in screen."),
        Map.of("key", "org.supportEmail", "label", "Support email", "type", "text",
               "group", "Brand", "default", "",
               "note", "Where learners are told to write when nothing else fits. "
                     + "Blank until you set it, because a plausible wrong address is "
                     + "worse than none."),
        Map.of("key", "onboarding.required", "label", "Require onboarding before the course opens",
               "type", "toggle", "group", "Onboarding", "default", "false",
               "note", "On, nothing opens until the form, the walkthrough and the call are "
                     + "done. Off, the three still show on the learner's home as a checklist "
                     + "but the course opens on first sign in."),
        Map.of("key", "auth.defaultPasswordDays", "label", "First password expires after",
               "type", "number", "group", "Brand", "default", "14",
               "note", "New accounts get a password made from their own name, which is "
                     + "easy to read out and easy to guess. It is forced out at first "
                     + "sign in, and this is how many days it keeps working if nobody "
                     + "ever signs in. Zero switches the expiry off."),
        Map.of("key", "onboarding.prereqVideoRef", "label", "Prerequisite video", "type", "video",
               "group", "Onboarding", "default", "",
               "note", "The walkthrough every learner watches before their modules open."),
        Map.of("key", "onboarding.skills", "label", "Skills learners rate themselves on",
               "type", "text", "group", "Onboarding",
               "default", "",
               "note", "Comma separated, and they appear on the information form. "
                     + "Leave blank to drop that question entirely."),
        Map.of("key", "onboarding.prereqTitle", "label", "Prerequisite video title", "type", "text",
               "group", "Onboarding", "default", "How this LMS works",
               "note", "What the learner sees above the player."),
        Map.of("key", "onboarding.overdueDays", "label", "Onboarding overdue after", "type", "number",
               "group", "Onboarding", "default", "7",
               "note", "Days in onboarding before the board flags someone to call."),
        Map.of("key", "risk.noLoginDays", "label", "At risk after no sign in", "type", "number",
               "group", "Follow up", "default", "7",
               "note", "Days without signing in before a learner is flagged."),
        Map.of("key", "risk.quietWeekMinutes", "label", "Quiet week under", "type", "number",
               "group", "Follow up", "default", "30",
               "note", "Study minutes in a week below which a learner counts as quiet."),
        Map.of("key", "batch.startDay", "label", "Batches start on", "type", "weekday",
               "group", "Batches", "default", "2",
               "note", "The Wednesday rule is derived from this day."),
        Map.of("key", "batch.previousDayJoin", "label", "Join day that goes to the previous batch",
               "type", "weekday", "group", "Batches", "default", "3",
               "note", "Someone joining on this day joins the batch that has just started."),
        Map.of("key", "video.watchedPercent", "label", "Counts as watched at", "type", "number",
               "group", "Learning", "default", "95",
               "note", "Percentage of a chapter video that marks it watched."),
        Map.of("key", "quiz.passScore", "label", "Test pass mark", "type", "number",
               "group", "Learning", "default", "60",
               "note", "Below this a learner is told to try the chapter again."),
        Map.of("key", "session.openBeforeMinutes", "label", "Join opens before", "type", "number",
               "group", "Sessions", "default", "10",
               "note", "Minutes before a session that the join button goes live."),
        Map.of("key", "session.openAfterMinutes", "label", "Join closes after", "type", "number",
               "group", "Sessions", "default", "15",
               "note", "Minutes after a session ends that the link stops working."),
        Map.of("key", "access.deviceLimit", "label", "Devices per account", "type", "number",
               "group", "Access", "default", "2",
               "note", "A third device is refused until an admin releases one."),
        Map.of("key", "access.idleMinutes", "label", "Session idle timeout", "type", "number",
               "group", "Access", "default", "120",
               "note", "Minutes of inactivity before a session is closed."),
        Map.of("key", "session.mentorsMayCreate", "label", "Mentors can create sessions",
               "type", "toggle", "group", "Sessions", "default", "true",
               "note", "On, a mentor sets up their own weekly sessions and one-offs and they "
                     + "appear on the shared week. Off, only an admin creates them and mentors "
                     + "see the week and take what they are given. Either way every mentor sees "
                     + "the whole week, and only an admin releases it to learners.")
    );

    public String get(String key, String fallback) {
        return settings.findByKey(key)
                .map(s -> s.getValue() == null ? null : String.valueOf(s.getValue()))
                .filter(v -> !v.isBlank())
                .orElse(fallback);
    }

    public int getInt(String key, int fallback) {
        try { return Integer.parseInt(get(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    public boolean getBool(String key, boolean fallback) {
        return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
    }

    public Map<String, Object> all() {
        Map<String, String> stored = new HashMap<>();
        settings.findAll().forEach(s ->
                stored.put(s.getKey(), s.getValue() == null ? "" : String.valueOf(s.getValue())));

        List<Map<String, Object>> rows = DEFINITIONS.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>(d);
            m.put("value", stored.getOrDefault(d.get("key"), d.get("default")));
            m.put("isDefault", !stored.containsKey(d.get("key")));
            if ("video".equals(d.get("type"))) {
                String ref = stored.getOrDefault(d.get("key"), "");
                m.put("videoSet", !ref.isBlank() && videos.findById(ref).isPresent());
            }
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", rows.stream().map(r -> String.valueOf(r.get("group"))).distinct().toList());
        out.put("settings", rows);
        return out;
    }

    public Map<String, Object> save(Map<String, String> values, String actorEmail) {
        Set<String> known = new HashSet<>();
        DEFINITIONS.forEach(d -> known.add(d.get("key")));
        int n = 0;
        for (var e : values.entrySet()) {
            if (!known.contains(e.getKey())) continue;   // nothing unknown gets written
            AppSetting s = settings.findByKey(e.getKey()).orElseGet(AppSetting::new);
            s.setKey(e.getKey());
            s.setValue(e.getValue());
            settings.save(s);
            n++;
        }
        activity.log(null, actorEmail, "SAVE_SETTINGS", "settings", n + " changed");
        refreshGatePolicy();
        return Map.of("saved", n);
    }

    /**
     * Push the onboarding flag out to where a document can read it.
     *
     * Called on save and once at startup. Anything else that writes app settings directly
     * would miss this, which is the cost of caching it; nothing else does today.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshGatePolicy() {
        GatePolicy.setOnboardingRequired(
                Boolean.parseBoolean(get("onboarding.required", "false")));
    }

    public Map<String, Object> reset(String key, String actorEmail) {
        settings.findByKey(key).ifPresent(settings::delete);
        activity.log(null, actorEmail, "RESET_SETTING", "settings", key);
        return Map.of("reset", true);
    }

    /**
     * A standalone video, for the settings that point at one: the walkthrough, an
     * induction recording. Same document as a chapter video, so the same grant flow
     * and the same rotation apply.
     */
    public Video saveVideo(String existingRef, String title, String externalId,
                           String kind, String provider, String actorEmail) {
        Video v = existingRef == null || existingRef.isBlank()
                ? new Video() : videos.findById(existingRef).orElse(new Video());
        v.setTitle(title);
        v.setExternalId(VideoRefs.parse(externalId, provider));
        v.setKind(kind == null ? "GUIDE" : kind);
        v.setProvider(provider == null || provider.isBlank() ? "YOUTUBE" : provider);
        v.setActive(true);
        Video saved = videos.save(v);
        activity.log(null, actorEmail, "SAVE_VIDEO", "video", title);
        return saved;
    }
}
