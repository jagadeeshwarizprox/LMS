package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;
@Document("quiz_questions")
public class QuizQuestion {
    @Id private String id;
    private String chapterId;
    private String prompt;
    private List<String> options = new ArrayList<>();
    private int correctIndex;
    private String explanation;
    private boolean draft;            // model drafted, not yet reviewed
    /*
     * What this question is worth.
     *
     * Every question counted the same, so a one line recall check and the question the
     * whole chapter builds towards were worth an identical share of the pass mark. One
     * is the default, which is exactly the old behaviour: a test nobody weights scores
     * the same as it always did.
     */
    private int marks = 1;

    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public int getCorrectIndex() { return correctIndex; }
    public void setCorrectIndex(int correctIndex) { this.correctIndex = correctIndex; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public int getMarks() { return marks <= 0 ? 1 : marks; }
    public void setMarks(int marks) { this.marks = marks <= 0 ? 1 : marks; }
    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
