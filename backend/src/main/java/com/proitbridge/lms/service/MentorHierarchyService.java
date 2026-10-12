package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who reports to whom, and therefore who a senior mentor can see.
 *
 * {@code User.reportsToId} has carried the chain from the beginning, and nothing ever
 * read it. Visibility was own learners plus anyone being covered for, so a lead holding
 * four mentors saw four empty screens and had to ask each of them how their cohort was
 * doing. The documentation has claimed reporting is transitive for as long as it has
 * been wrong.
 *
 * Two guards, because the chain is edited by hand. Depth is capped, and a visited set
 * stops a cycle: somebody will eventually make two people report to each other, and the
 * result of that should be a slightly odd tree rather than a request that never returns.
 */
@Service
public class MentorHierarchyService {

    private static final int MAX_DEPTH = 12;

    /** How long a computed subtree is trusted. Staff edits also drop it at once. */
    private static final long TTL_MS = 60_000;

    private static final List<User.Role> STAFF =
            List.of(User.Role.MENTOR, User.Role.ADMIN, User.Role.SUPER_ADMIN);

    private final UserRepository users;

    /*
     * Every visibility check on a mentor request asks one of these two questions, and
     * the answer only changes when somebody edits the staff tree. A minute's cache per
     * person keeps a lead's screen from walking the tree on every click; saveStaff,
     * updateStaff and setStaffActive call invalidate() so an edit shows at once.
     */
    private record Cached<T>(T value, long at) {
        boolean fresh() { return System.currentTimeMillis() - at < TTL_MS; }
    }
    private final Map<String, Cached<List<User>>> reportsCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<Set<String>>> belowCache = new ConcurrentHashMap<>();

    public MentorHierarchyService(UserRepository users) {
        this.users = users;
    }

    /** Drop every cached subtree. Called whenever a staff account is saved or switched. */
    public void invalidate() {
        reportsCache.clear();
        belowCache.clear();
    }

    /** The people who report directly to this person, active staff only. */
    public List<User> directReports(String userId) {
        if (userId == null) return List.of();
        Cached<List<User>> hit = reportsCache.get(userId);
        if (hit != null && hit.fresh()) return hit.value();
        /* off the reportsToId index: the people reporting to one person, not everybody */
        List<User> out = users.findByReportsToIdInAndRoleIn(List.of(userId), STAFF).stream()
                .filter(User::isActive)
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareTo)))
                .toList();
        reportsCache.put(userId, new Cached<>(out, System.currentTimeMillis()));
        return out;
    }

    /**
     * Everyone below this person in the tree, at any depth, not including themselves.
     *
     * Walked level by level off the reportsToId index: one $in read per level, holding
     * only the ids found on the level above. A mentor with nobody under them costs one
     * empty indexed read; nothing reads the whole staff list, let alone every user.
     */
    public Set<String> everyoneBelow(String userId) {
        if (userId == null) return Set.of();
        Cached<Set<String>> hit = belowCache.get(userId);
        if (hit != null && hit.fresh()) return hit.value();

        Set<String> below = new LinkedHashSet<>();
        Set<String> level = Set.of(userId);
        for (int depth = 0; depth < MAX_DEPTH && !level.isEmpty(); depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (User u : users.findByReportsToIdInAndRoleIn(level, STAFF)) {
                if (!u.isActive()) continue;
                String id = u.getId();
                if (id.equals(userId)) continue;               // a cycle back to the top
                if (below.add(id)) next.add(id);               // already seen: a cycle
            }
            level = next;
        }
        Set<String> out = Collections.unmodifiableSet(below);
        belowCache.put(userId, new Cached<>(out, System.currentTimeMillis()));
        return out;
    }

    /** True when this person sits above that one, at any depth. */
    public boolean isAbove(String userId, String otherId) {
        return everyoneBelow(userId).contains(otherId);
    }

    /**
     * The chain upward, nearest first. Used to say whose learner this actually is on a
     * screen a senior is reading, so nothing looks reassigned to them.
     */
    public List<User> chainAbove(String userId) {
        List<User> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String at = userId;
        for (int i = 0; i < MAX_DEPTH; i++) {
            User u = users.findById(at).orElse(null);
            if (u == null || u.getReportsToId() == null) break;
            if (!seen.add(u.getReportsToId())) break;
            User boss = users.findById(u.getReportsToId()).orElse(null);
            if (boss == null) break;
            chain.add(boss);
            at = boss.getId();
        }
        return chain;
    }
}
