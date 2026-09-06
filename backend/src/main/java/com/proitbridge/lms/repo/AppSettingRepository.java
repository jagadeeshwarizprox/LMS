package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface AppSettingRepository extends MongoRepository<AppSetting, String> {
    Optional<AppSetting> findByKey(String key);
}
