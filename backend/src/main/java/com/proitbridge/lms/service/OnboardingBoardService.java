package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Stages one and two, made visible.
 *
 * The lifecycle has five stages but the LMS only ever showed the learner's own three
 * gates, so nobody on the admin side could see who was stuck where. This is that
 * board: every learner in onboarding, which step they are on, and how long they have
 * been sitting there.
 */
@Service
public class OnboardingBoardService {

    private final LearnerRepository learners;
    private final UserRepository users;
    private final BatchRepository batches;
    private final IntakeFormRepository intakeForms;
    private final MailLogRepository mailLog;

    public OnboardingBoardService(LearnerRepository learners, UserRepository users,
                                  BatchRepository batches, IntakeFormRepository intakeForms,
                                  MailLogRepository mailLog) {
        this.learners = learners; this.users = users; this.batches = batches;
        this.intakeForms = intakeForms; this.mailLog = mailLog;
    }

    /** The steps in order. The first one not done is where that learner actually is. */
    private static final String[] STEPS = {
        "Account created", "Credentials sent", "First sign in", "Group link set",
        "Information form", "Prerequisite video", "Onboarding call or induction"
    };

    public Map<String, Object> board() {
        List<Map<String, Object>> rows = new ArrayList<>();
        int[] stuckAt = new int[STEPS.length];

        for (Learner l : learners.findAll()) {
            if (l.gatesCleared()) continue;             // done with onboarding, not this board
            User u = users.findById(l.getUserId()).orElse(null);
            if (u == null) continue;

            boolean credsSent = !mailLog.findByToEmailOrderBySentAtDesc(u.getEmail()).isEmpty();
            boolean signedIn = u.getLastLoginAt() != null;
            boolean group = l.getWhatsappGroupLink() != null && !l.getWhatsappGroupLink().isBlank();

            boolean[] done = {
                true, credsSent, signedIn, group,
                l.isGateFormDone(), l.isGatePrereqDone(), l.isGateCallDone()
            };

            int at = STEPS.length;
            for (int i = 0; i < done.length; i++) {
                if (!done[i]) { at = i; break; }
            }
            if (at < STEPS.length) stuckAt[at]++;

            long waiting = l.getJoinedOn() == null ? 0
                    : ChronoUnit.DAYS.between(l.getJoinedOn(), LocalDate.now());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("learnerId", l.getId());
            row.put("name", u.getFullName());
            row.put("email", u.getEmail());
            row.put("trackType", l.getTrackType());
            row.put("batch", l.getBatchId() == null ? null
                    : batches.findById(l.getBatchId()).map(Batch::getCode).orElse(null));
            row.put("onHold", l.isOnHold());
            row.put("steps", steps(done));
            row.put("stuckOn", at < STEPS.length ? STEPS[at] : "Done");
            row.put("daysWaiting", waiting);
            row.put("formSections", intakeForms.findByLearnerId(l.getId())
                    .map(f -> f.getCompletedSections().size()).orElse(0));
            // a week in onboarding is the point at which somebody should pick up the phone
            row.put("overdue", waiting >= 7);
            rows.add(row);
        }

        rows.sort((a, b) -> Long.compare((Long) b.get("daysWaiting"), (Long) a.get("daysWaiting")));

        List<Map<String, Object>> funnel = new ArrayList<>();
        for (int i = 0; i < STEPS.length; i++) {
            funnel.add(Map.of("step", STEPS[i], "waiting", stuckAt[i]));
        }

        return Map.of(
                "rows", rows,
                "funnel", funnel,
                "total", rows.size(),
                "overdue", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("overdue"))).count());
    }

    private List<Map<String, Object>> steps(boolean[] done) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < STEPS.length; i++) {
            out.add(Map.of("label", STEPS[i], "done", done[i]));
        }
        return out;
    }

    /** Mark the group link, the one step in stage one the LMS can actually hold. */
    public Learner setGroupLink(String learnerId, String link) {
        Learner l = learners.findById(learnerId).orElseThrow();
        l.setWhatsappGroupLink(link);
        return learners.save(l);
    }

    /**
     * Modules the learner already rated Advanced in the intake form become review only,
     * which is what section 4 of that form promised and nothing ever did.
     */
    public Map<String, Object> suggestFastForward(String learnerId,
                                                  List<Map<String, String>> topicNames) {
        Learner l = learners.findById(learnerId).orElseThrow();
        IntakeForm f = intakeForms.findByLearnerId(learnerId).orElse(null);
        if (f == null) return Map.of("suggested", List.of());
        Object tech = f.getSections().get("technical");
        if (!(tech instanceof Map<?, ?> m)) return Map.of("suggested", List.of());

        List<Map<String, String>> suggested = new ArrayList<>();
        for (Map<String, String> t : topicNames) {
            Object level = m.get("skill_" + t.get("name"));
            if (level != null && "Advanced".equalsIgnoreCase(String.valueOf(level))) {
                suggested.add(Map.of("moduleId", t.get("id"), "name", t.get("name"),
                        "level", String.valueOf(level)));
            }
        }
        return Map.of("suggested", suggested,
                "applied", l.getFastForwardTopicIds());
    }

    public Learner applyFastForward(String learnerId, Set<String> moduleIds) {
        Learner l = learners.findById(learnerId).orElseThrow();
        l.setFastForwardTopicIds(new LinkedHashSet<>(moduleIds == null ? Set.of() : moduleIds));
        return learners.save(l);
    }
}
