package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByLoginIdIgnoreCase(String loginId);
    List<User> findByRole(User.Role role);
    List<User> findByReportsToId(String id);
    boolean existsByEmailIgnoreCase(String email);
}
