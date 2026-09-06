package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;
@Document("case_studies")
public class CaseStudy {
    @Id private String id;
    private String title;
    private String moduleId;
    private String summary;
    private String url;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
