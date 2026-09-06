package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.util.*;

/** Per track feature toggles. Configured by Super Admin, never hard coded. */
@Document("features")
public class Feature {
    @Id private String id;
    @Indexed(unique = true) private String key;
    private String label;
    private boolean forPremium = true;
    private boolean forBatch = true;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isForPremium() { return forPremium; }
    public void setForPremium(boolean forPremium) { this.forPremium = forPremium; }
    public boolean isForBatch() { return forBatch; }
    public void setForBatch(boolean forBatch) { this.forBatch = forBatch; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
}
