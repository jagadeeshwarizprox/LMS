package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.util.*;

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

    private final UserRepository users;

    public MentorHierarchyService(UserRepository users) {
        this.users = users;
    }

    /** The people who report directly to this person, active ones only. */
    public List<User> directReports(String userId) {
        return users.findAll().stream()
                .filter(User::isActive)
                .filter(u -> userId.equals(u.getReportsToId()))
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * Everyone below this person in the tree, at any depth, not including themselves.
     * One pass over the user list builds the child index, so depth costs nothing extra.
     */
    public Set<String> everyoneBelow(String userId) {
        Map<String, List<String>> children = new HashMap<>();
        for (User u : users.findAll()) {
            if (!u.isActive() || u.getReportsToId() == null) continue;
            children.computeIfAbsent(u.getReportsToId(), k -> new ArrayList<>()).add(u.getId());
        }

        Set<String> below = new LinkedHashSet<>();
        Deque<String[]> queue = new ArrayDeque<>();
        queue.add(new String[] { userId, "0" });

        while (!queue.isEmpty()) {
            String[] step = queue.removeFirst();
            int depth = Integer.parseInt(step[1]);
            if (depth >= MAX_DEPTH) continue;
            for (String childId : children.getOrDefault(step[0], List.of())) {
                if (childId.equals(userId)) continue;          // a cycle back to the top
                if (!below.add(childId)) continue;             // already seen: a cycle
                queue.add(new String[] { childId, String.valueOf(depth + 1) });
            }
        }
        return below;
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
