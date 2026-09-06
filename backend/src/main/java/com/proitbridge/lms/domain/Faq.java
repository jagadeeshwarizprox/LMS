package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/** A question answered where it is actually asked, not buried in a help centre. */
@Document("faqs")
public class Faq {
    @Id private String id;
    private String question;
    private String answer;
    private String category;
    private String placement = "HELP";   // LOGIN|GATES|CHAPTER|SESSIONS|MOCK|BATCH|PROFILE|HELP
    private String trackScope = "BOTH";  // PREMIUM|BATCH|BOTH
    private int position;
    private boolean active = true;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPlacement() { return placement; }
    public void setPlacement(String placement) { this.placement = placement; }
    public String getTrackScope() { return trackScope; }
    public void setTrackScope(String trackScope) { this.trackScope = trackScope; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
