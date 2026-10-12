package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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
    private final MongoTemplate mongo;

    public OnboardingBoardService(LearnerRepository learners, UserRepository users,
                                  BatchRepository batches, IntakeFormRepository intakeForms,
                                  MongoTemplate mongo) {
        this.learners = learners; this.users = users; this.batches = batches;
        this.intakeForms = intakeForms; this.mongo = mongo;
    }

    /** The steps in order. The first one not done is where that learner actually is. */
    private static final String[] STEPS = {
        "Account created", "Credentials sent", "First sign in", "Group link set",
        "Information form", "Prerequisite video", "Onboarding call or induction"
    };

    /** One open learner, reduced to what the funnel and the ordering need. */
    private record Seat(String learnerId, String userId, int at, long waiting) { }

    /**
     * The board, a page at a time, with the funnel still counting everybody.
     *
     * This used to load every learner, skip the ones done with onboarding, and then do a
     * user lookup, a mail-log read and an intake form read per remaining learner. At
     * twenty thousand on the register that is the whole collection in memory and
     * thousands of round trips per page view.
     *
     * Now it runs in two halves.
     *
     * The funnel. Which step a learner is stuck on depends on two facts that do not live
     * on the learner (were credentials mailed, have they signed in), so a pure count per
     * step is not possible without copying those facts onto the learner row. Instead the
     * open learners, and only those (selected by query, AdminQueries.stillOnboarding), are
     * walked in chunks of 500 by id, reading seven fields each. Per chunk there is one
     * user read (email and last sign in only) and one distinct read on the mail log for
     * the chunk's addresses. What stays in memory is a four-field seat per open learner.
     * In-onboarding and overdue totals come out of the same pass so the numbers on the
     * page agree with each other.
     *
     * The rows. The seats are filtered to the chosen step, ordered longest waiting
     * first, and only the requested page is turned into full rows, with its learners,
     * users, batches and intake forms loaded in one read each.
     */
    public Map<String, Object> board(String stuckOn, int page, int size) {
        List<Seat> seats = new ArrayList<>();
        int[] stuckAt = new int[STEPS.length];
        LocalDate today = LocalDate.now();

        String after = null;
        while (true) {
            Criteria c = AdminQueries.stillOnboarding();
            if (after != null) c = new Criteria().andOperator(c, Criteria.where("_id").gt(after));
            Query q = new Query(c).with(Sort.by(Sort.Direction.ASC, "_id")).limit(AdminQueries.CHUNK);
            q.fields().include("_id", "userId", "trackType", "joinedOn", "whatsappGroupLink",
                    "gateFormDone", "gatePrereqDone", "gateCallDone", "inductionWatched");
            List<Learner> chunk = mongo.find(q, Learner.class);
            if (chunk.isEmpty()) break;
            after = chunk.get(chunk.size() - 1).getId();

            Set<String> userIds = new HashSet<>();
            chunk.forEach(l -> { if (l.getUserId() != null) userIds.add(l.getUserId()); });
            Query uq = new Query(Criteria.where("_id").in(userIds));
            uq.fields().include("_id", "email", "lastLoginAt");
            Map<String, User> who = new HashMap<>();
            mongo.find(uq, User.class).forEach(u -> who.put(u.getId(), u));
            Set<String> emails = new HashSet<>();
            who.values().forEach(u -> { if (u.getEmail() != null) emails.add(u.getEmail()); });
            Set<String> mailed = emails.isEmpty() ? Set.of() : new HashSet<>(mongo.findDistinct(
                    new Query(Criteria.where("toEmail").in(emails)), "toEmail", MailLog.class, String.class));

            for (Learner l : chunk) {
                if (l.gatesCleared()) continue;      // same rule as the query, belt and braces
                User u = who.get(l.getUserId());
                if (u == null) continue;
                boolean[] done = doneFor(l, u, mailed.contains(u.getEmail()));
                int at = firstOpen(done);
                if (at < STEPS.length) stuckAt[at]++;
                long waiting = l.getJoinedOn() == null ? 0 : ChronoUnit.DAYS.between(l.getJoinedOn(), today);
                seats.add(new Seat(l.getId(), l.getUserId(), at, waiting));
            }
            if (chunk.size() < AdminQueries.CHUNK) break;
        }

        long overdue = seats.stream().filter(x -> x.waiting() >= 7).count();

        List<Seat> chosen = seats.stream()
                .filter(x -> stuckOn == null || stuckOn.isBlank()
                        || stuckOn.equals(x.at() < STEPS.length ? STEPS[x.at()] : "Done"))
                .sorted(Comparator.comparingLong(Seat::waiting).reversed()
                        .thenComparing(Seat::learnerId))
                .toList();
        int from = (int) Math.min((long) page * size, chosen.size());
        List<Seat> slice = chosen.subList(from, Math.min(chosen.size(), from + size));

        List<Map<String, Object>> funnel = new ArrayList<>();
        for (int i = 0; i < STEPS.length; i++) {
            funnel.add(Map.of("step", STEPS[i], "waiting", stuckAt[i]));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rowsFor(slice));
        out.put("funnel", funnel);
        out.put("total", seats.size());
        out.put("overdue", overdue);
        /* the pager reads these: how many match the step filter, and where we are */
        out.put("matching", chosen.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    /** The seven steps for one learner, in board order. */
    private static boolean[] doneFor(Learner l, User u, boolean credsSent) {
        boolean signedIn = u.getLastLoginAt() != null;
        boolean group = l.getWhatsappGroupLink() != null && !l.getWhatsappGroupLink().isBlank();
        return new boolean[] {
            true, credsSent, signedIn, group,
            l.isGateFormDone(), l.isGatePrereqDone(), l.isGateCallDone()
        };
    }

    private static int firstOpen(boolean[] done) {
        for (int i = 0; i < done.length; i++) if (!done[i]) return i;
        return STEPS.length;
    }

    /** Full rows for one page of seats, every lookup done once for the page. */
    private List<Map<String, Object>> rowsFor(List<Seat> slice) {
        if (slice.isEmpty()) return List.of();
        List<String> ids = slice.stream().map(Seat::learnerId).toList();
        Map<String, Learner> byId = new HashMap<>();
        learners.findAllById(ids).forEach(l -> byId.put(l.getId(), l));
        Map<String, User> who = new HashMap<>();
        users.findAllById(slice.stream().map(Seat::userId).collect(Collectors.toSet()))
                .forEach(u -> who.put(u.getId(), u));
        Map<String, String> batchCode = new HashMap<>();
        batches.findAllById(byId.values().stream().map(Learner::getBatchId)
                .filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(b -> batchCode.put(b.getId(), b.getCode()));
        Map<String, Integer> sections = new HashMap<>();
        mongo.find(new Query(Criteria.where("learnerId").in(ids)), IntakeForm.class)
                .forEach(f -> sections.put(f.getLearnerId(), f.getCompletedSections().size()));
        Set<String> mailed = new HashSet<>(mongo.findDistinct(
                new Query(Criteria.where("toEmail").in(who.values().stream().map(User::getEmail)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))),
                "toEmail", MailLog.class, String.class));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Seat seat : slice) {
            Learner l = byId.get(seat.learnerId());
            User u = who.get(seat.userId());
            if (l == null || u == null) continue;
            boolean[] done = doneFor(l, u, mailed.contains(u.getEmail()));
            int at = firstOpen(done);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("learnerId", l.getId());
            row.put("name", u.getFullName());
            row.put("email", u.getEmail());
            row.put("trackType", l.getTrackType());
            row.put("batch", l.getBatchId() == null ? null : batchCode.get(l.getBatchId()));
            row.put("onHold", l.isOnHold());
            row.put("steps", steps(done));
            row.put("stuckOn", at < STEPS.length ? STEPS[at] : "Done");
            row.put("daysWaiting", seat.waiting());
            row.put("formSections", sections.getOrDefault(l.getId(), 0));
            // a week in onboarding is the point at which somebody should pick up the phone
            row.put("overdue", seat.waiting() >= 7);
            rows.add(row);
        }
        return rows;
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
