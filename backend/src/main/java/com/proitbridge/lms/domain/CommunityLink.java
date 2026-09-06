package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/** Links a cohort actually needs: the group, the community, the shared drive. */
@Document("community_links")
public class CommunityLink {
    @Id private String id;
    private String label;
    private String url;
    private String trackScope = "BOTH";
    private String batchId;
    private int position;
    private boolean active = true;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTrackScope() { return trackScope; }
    public void setTrackScope(String trackScope) { this.trackScope = trackScope; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
