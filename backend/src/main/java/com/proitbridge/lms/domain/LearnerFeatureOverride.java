package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/** Admin can toggle a feature for one learner without changing the track default. */
@Document("learner_feature_overrides")
public class LearnerFeatureOverride {
    @Id private String id;
    private String learnerId;
    private String featureKey;
    private boolean enabled;

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
