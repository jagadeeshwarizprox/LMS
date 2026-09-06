package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/** Every model call is kept: what was asked, what came back, and which model said it. */
@Document("ai_interactions")
public class AiInteraction {
    @Id private String id;
    private String learnerId;
    private String kind;                 // TEACH_BACK|DOUBT|QUIZ_DRAFT|FAQ
    private String chapterId;
    private String prompt;
    private String response;
    private String model;
    private boolean fallback;            // true when Ollama was unreachable
    private long latencyMs;
    private Instant createdAt = Instant.now();

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
