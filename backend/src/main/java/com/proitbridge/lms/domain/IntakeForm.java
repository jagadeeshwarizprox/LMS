package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/**
 * The learner's answers, keyed by section.
 *
 * Which sections exist is configured, not compiled in: see FormSection. Each saves
 * independently and carries its own completion state, so a long form can be filled in
 * over several sittings.
 */
@Document("intake_forms")
public class IntakeForm {
    @Id private String id;
    @Indexed(unique = true) private String learnerId;
    private String profileType;                 // FRESHER|WORKING|CAREER_GAP
    private Map<String, Object> sections = new LinkedHashMap<>();
    private Set<String> completedSections = new LinkedHashSet<>();
    private Instant submittedAt;
    private Instant updatedAt = Instant.now();

    public String getProfileType() { return profileType; }
    public void setProfileType(String profileType) { this.profileType = profileType; }
    public Map<String, Object> getSections() { return sections; }
    public void setSections(Map<String, Object> sections) { this.sections = sections; }
    public Set<String> getCompletedSections() { return completedSections; }
    public void setCompletedSections(Set<String> completedSections) { this.completedSections = completedSections; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
}
