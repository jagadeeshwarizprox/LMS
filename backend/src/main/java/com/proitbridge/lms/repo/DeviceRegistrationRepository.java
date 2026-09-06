package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface DeviceRegistrationRepository extends MongoRepository<DeviceRegistration, String> {
    List<DeviceRegistration> findByUserIdAndRevokedFalse(String userId);
    Optional<DeviceRegistration> findByUserIdAndDeviceId(String userId, String deviceId);
    List<DeviceRegistration> findByUserId(String userId);
}
