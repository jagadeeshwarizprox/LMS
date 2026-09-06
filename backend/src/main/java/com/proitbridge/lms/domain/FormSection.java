package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One section of the information form learners fill in at onboarding.
 *
 * These were eight names in a Java constant, which meant what you ask a learner was
 * decided by whoever wrote the code. What an organisation needs to know about the people
 * it teaches is its own business, so it lives here.
 *
 * The gate only counts sections that are required, so adding an optional one never
 * blocks anybody who has already finished.
 */
@Document("form_sections")
public class FormSection {

    @Id private String id;

    /** Stable, used as the key inside the form's saved answers. */
    private String key;
    private String label;
    private String note;
    private int position;
    private boolean required = true;
    private boolean active = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
