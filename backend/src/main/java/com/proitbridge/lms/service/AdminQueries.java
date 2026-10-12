package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.Learner;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The small pieces every admin list needs once the register holds twenty thousand people.
 *
 * Paging: one page shape for every big list, so the screens share one pager component and
 * nobody has to remember whether this endpoint calls it "items" or "content". The shape
 * is {rows, total, page, size}, page counted from zero.
 *
 * Learner criteria: "has finished onboarding" is a method on Learner (gatesCleared). Learner
 * stores its answer as the indexed {@code onboarded} field on every save, so a count or a
 * page asks that one field. The gate-by-gate form is kept beside it for the backfill; if
 * gatesCleared changes, that has to change with it.
 */
public final class AdminQueries {

    private AdminQueries() { }

    /** A page of fifty unless asked otherwise, never more than two hundred. */
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    /** How many ids a background job holds at once while it walks a big collection. */
    public static final int CHUNK = 500;

    public static int size(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_SIZE;
        return Math.min(requested, MAX_SIZE);
    }

    public static int page(Integer requested) {
        return requested == null || requested < 0 ? 0 : requested;
    }

    public static Map<String, Object> pageOf(List<?> rows, long total, int page, int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("total", total);
        m.put("page", page);
        m.put("size", size);
        return m;
    }

    /**
     * What somebody typed, made safe to put inside a regex.
     *
     * A search box is not a regex box: "c++" or "a.b@x.com" must match themselves, and a
     * stray bracket must not turn into a 500. Pattern.quote wraps the text in \Q..\E,
     * which the Mongo regex engine (PCRE) reads the same way Java does.
     */
    public static String literal(String typed) {
        return Pattern.quote(typed.trim());
    }

    /** True when the trimmed text is worth searching for. */
    public static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Learner.gatesCleared(true), as a query: the stored {@code onboarded} flag, which
     * Learner writes from the gates on every save (onboarded_id index).
     */
    public static Criteria onboarded() {
        return Criteria.where("onboarded").is(true);
    }

    /**
     * The exact negation of {@link #onboarded()}: some gate still open. `ne(true)` so a
     * row written before the flag existed counts as not done until the backfill sets it.
     */
    public static Criteria stillOnboarding() {
        return Criteria.where("onboarded").ne(true);
    }

    /**
     * The same rule read off the gate fields themselves. Only OnboardedFlagBackfill uses
     * these, to set the stored flag on rows written before it existed. If gatesCleared
     * changes, this has to change with it.
     *
     * form, prerequisite and call all done, and for a batch learner the induction too.
     */
    public static Criteria onboardedByGates() {
        return new Criteria().andOperator(
                Criteria.where("gateFormDone").is(true),
                Criteria.where("gatePrereqDone").is(true),
                Criteria.where("gateCallDone").is(true),
                new Criteria().orOperator(
                        Criteria.where("trackType").ne(Learner.TrackType.BATCH.name()),
                        Criteria.where("inductionWatched").is(true)));
    }

    /** The exact negation of {@link #onboardedByGates()}, one open gate after another. */
    public static Criteria stillOnboardingByGates() {
        return new Criteria().orOperator(
                Criteria.where("gateFormDone").ne(true),
                Criteria.where("gatePrereqDone").ne(true),
                Criteria.where("gateCallDone").ne(true),
                new Criteria().andOperator(
                        Criteria.where("trackType").is(Learner.TrackType.BATCH.name()),
                        Criteria.where("inductionWatched").ne(true)));
    }

    /** Several criteria and-ed together, or an empty criteria when there are none. */
    public static Criteria all(List<Criteria> parts) {
        if (parts.isEmpty()) return new Criteria();
        if (parts.size() == 1) return parts.get(0);
        return new Criteria().andOperator(parts);
    }

    /**
     * Hands a big id list to the consumer CHUNK at a time.
     *
     * An $in of twenty thousand ids is a legal query and a bad habit: one huge request,
     * one huge result. Five hundred at a time keeps both small and lets the work stream.
     */
    public static <T> void inChunks(Collection<T> ids, Consumer<List<T>> work) {
        List<T> all = List.copyOf(ids);
        for (int i = 0; i < all.size(); i += CHUNK) {
            work.accept(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
    }
}
