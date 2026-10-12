package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByLoginIdIgnoreCase(String loginId);
    List<User> findByRole(User.Role role);
    List<User> findByReportsToId(String id);
    /* one level of the reporting tree, off the reports_to index */
    List<User> findByReportsToIdInAndRoleIn(Collection<String> ids, Collection<User.Role> roles);
    boolean existsByEmailIgnoreCase(String email);

    /*
     * Exact matches, straight off the unique indexes.
     *
     * The IgnoreCase forms above become an anchored case-insensitive regex, which Mongo
     * answers by walking every key in the index: harmless at two hundred accounts,
     * twenty thousand comparisons per lookup at twenty thousand. Emails are stored
     * lower case (provisioning, the seeder and staff saves all fold them) and login IDs
     * are slugs, so a lower-cased exact match finds the account; callers fall back to the
     * IgnoreCase form only for the handful of staff rows written before that rule.
     */
    Optional<User> findByEmail(String email);
    Optional<User> findByLoginId(String loginId);

    /* staff by role, off the role_active index */
    List<User> findByRoleAndActiveTrue(User.Role role);
    List<User> findByRoleIn(Collection<User.Role> roles);
    long countByRole(User.Role role);
}
