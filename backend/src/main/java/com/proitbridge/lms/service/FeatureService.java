package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.Feature;
import com.proitbridge.lms.domain.Learner;
import com.proitbridge.lms.repo.FeatureRepository;
import com.proitbridge.lms.repo.LearnerFeatureOverrideRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** What a learner can see is configured, not compiled in. */
@Service
public class FeatureService {

    private final FeatureRepository features;
    private final LearnerFeatureOverrideRepository overrides;

    public FeatureService(FeatureRepository features, LearnerFeatureOverrideRepository overrides) {
        this.features = features;
        this.overrides = overrides;
    }

    public Map<String, Boolean> resolve(Learner learner) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        boolean premium = learner.getTrackType() == Learner.TrackType.PREMIUM;
        for (Feature f : features.findAll()) {
            map.put(f.getKey(), premium ? f.isForPremium() : f.isForBatch());
        }
        for (var o : overrides.findByLearnerId(learner.getId())) {
            map.put(o.getFeatureKey(), o.isEnabled());
        }
        return map;
    }

    public boolean enabled(Learner learner, String key) {
        return Boolean.TRUE.equals(resolve(learner).get(key));
    }

    /**
     * The same question, but a key nobody has defined means yes.
     *
     * `enabled` answers no for a key that is not in the features collection, which is the
     * right answer for "is this switched on" and the wrong one for "may this happen". The
     * seeder only fills an empty database, so a key added after an install exists in the
     * code and not in that database, and a plain `enabled` check would then hide the
     * thing from everybody with no way for an admin to see why: the switch they would
     * reach for is not on their screen either.
     *
     * Absent therefore means unrestricted. Present and off means off.
     */
    public boolean allowedUnlessConfiguredOff(Learner learner, String key) {
        if (features.findAll().stream().noneMatch(f -> key.equals(f.getKey()))) return true;
        return enabled(learner, key);
    }

    /**
     * Whether a learner may see sessions of this kind.
     *
     * Five toggles on the Track features screen all ask the same question in different
     * words: live_sessions, doubt_clearing, group_doubt, industry_sessions and
     * project_sessions. They were read by nothing, so switching any of them off changed
     * nothing and an admin had no way to find that out.
     *
     * They are one filter, not five. A kind with no toggle behind it is always allowed,
     * because a session type nobody configured is not a session type anybody meant to
     * hide.
     */
    public boolean sessionKindAllowed(Learner learner, String kind) {
        String key = switch (kind == null ? "" : kind) {
            case "LIVE", "INTERACTIVE" -> "live_sessions";
            case "INDUSTRY" -> "industry_sessions";
            case "DOUBT" -> "doubt_clearing";
            case "GROUP_DOUBT" -> "group_doubt";
            case "PROJECT" -> "project_sessions";
            /* a recap is a re-teach of the live session, so it follows the same toggle:
               a track that gets the session gets the chance to see it again */
            case "RECAP" -> "live_sessions";
            default -> null;
        };
        return key == null || allowedUnlessConfiguredOff(learner, key);
    }

    /**
     * Refuse rather than return, so a caller cannot forget to act on the answer.
     *
     * The check belongs on the server. Hiding a button is presentation; refusing the
     * request is the rule.
     */
    public void require(Learner learner, String key, String whenOff) {
        if (!enabled(learner, key)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, whenOff);
        }
    }

    public List<Feature> all() {
        return features.findAll();
    }
}
