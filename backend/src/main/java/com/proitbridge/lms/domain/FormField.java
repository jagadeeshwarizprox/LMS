package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * One question on the intake form.
 *
 * The sections were configurable and the questions inside them were not: they were JSX,
 * so an organisation could rename "Education" and switch it off but could not change a
 * single thing it asked. That is the wrong half to make editable, because the questions
 * are exactly what differs between one client and the next.
 *
 * Answers were already stored as a free-form map keyed by section, so nothing here needs
 * a migration. The `key` is what an answer is filed under and is the one thing that must
 * not change once anybody has answered.
 *
 * `showWhen` handles the one real branch the form has: the professional section asks
 * completely different questions of a fresher, somebody working, and somebody returning
 * from a break. A field with a showWhen only appears when the profile type matches.
 */
@Document("form_fields")
public class FormField {

    @Id private String id;

    /** Which section this belongs to: matches FormSection.key. */
    private String sectionKey;

    /** Stable answer key. Renaming this orphans every answer already given. */
    private String key;

    private String label;
    private String help;

    /** TEXT|LONG_TEXT|NUMBER|DATE|MONTH|CHOICE|MULTI_CHOICE|CHECKBOX|LINK|EMAIL|PHONE */
    private String type = "TEXT";

    private List<String> options = new ArrayList<>();

    /** Profile types this field appears for. Empty means always. */
    private List<String> showWhen = new ArrayList<>();

    private boolean required;
    private int position;

    /**
     * Read only fields are shown filled in from the learner's record and cannot be
     * edited here, because the record is the source and a second editable copy of an
     * email address is a way to end up with two different ones.
     */
    private boolean readOnly;

    /**
     * Deactivated rather than deleted once answers exist. Deleting a question orphans
     * every answer ever given to it, and those answers are worth keeping.
     */
    private boolean active = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSectionKey() { return sectionKey; }
    public void setSectionKey(String sectionKey) { this.sectionKey = sectionKey; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getHelp() { return help; }
    public void setHelp(String help) { this.help = help; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options == null ? new ArrayList<>() : options; }
    public List<String> getShowWhen() { return showWhen; }
    public void setShowWhen(List<String> showWhen) { this.showWhen = showWhen == null ? new ArrayList<>() : showWhen; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
