package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/**
 * One row per signed-in device. Two are allowed. A third needs an admin to
 * release a slot, which puts a human between a shared password and a new viewer.
 */
@Document("devices")
public class DeviceRegistration {
    @Id private String id;
    private String userId;
    private String deviceId;          // opaque id generated in the browser
    private String label;             // coarse: "Chrome on Windows"
    private String lastIp;
    private Instant firstSeen = Instant.now();
    private Instant lastSeen = Instant.now();
    private boolean revoked;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getLastIp() { return lastIp; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }
    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
