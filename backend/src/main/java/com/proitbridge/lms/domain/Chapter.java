package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/**
 * A chapter groups the topics a learner works through together, and carries the three
 * things that are assessed once for the whole group: one test, one assignment, one
 * teach back.
 *
 * A chapter used to hold the video as well, which meant every video came with its own
 * test and its own task. That is not how the material is actually taught. Four short
 * videos on control flow are one thing to be tested on, not four. So the video moved
 * down to {@link Topic} and the assessment stayed here.
 */
@Document("chapters")
public class Chapter {

    @Id private String id;
    @Indexed private String moduleId;
    private String title;
    private String summary;           // one line, shown under the title in the builder
    private int position;
    private boolean active = true;

    /*
     * These three are never null. A chapter saved before one of them existed comes back
     * from Mongo with it unset, and every caller writes c.getTest().isEnabled() without
     * a guard, so a single old row turned the whole learner record into a 500. The
     * setters refuse null so that the field defaults survive deserialisation.
     */
    private Test test = new Test();
    private AssignmentSpec assignment = new AssignmentSpec();
    private TeachBack teachback = new TeachBack();

    /** One test per chapter. Questions live in their own collection, keyed on the chapter. */
    public static class Test {
        private boolean enabled = true;
        private int passMark = 60;
        private int attempts = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPassMark() { return passMark; }
        public void setPassMark(int passMark) { this.passMark = passMark; }
        public int getAttempts() { return attempts; }
        public void setAttempts(int attempts) { this.attempts = attempts; }
    }

    /**
     * One assignment per chapter. This is the brief, not a submission: what a learner
     * hands in is an {@link com.proitbridge.lms.domain.Assignment} document.
     */
    public static class AssignmentSpec {
        private boolean enabled = true;
        private String title;
        private String brief;
        private int dueDays = 7;
        private int marks = 20;
        private String allow = "pdf, ipynb, py, zip";
        private boolean review = true;      // mentor scores and approves
        private boolean resubmit = true;    // learner may upload a corrected version
        private boolean lockNext = true;    // next chapter waits for a submission
        private List<String> fileIds = new ArrayList<>();   // brief, dataset, starter, rubric
        /**
         * The rubric this task is marked against.
         *
         * Assignment.rubricId existed and the mentor's review screen renders a rubric
         * properly, but there was nowhere to put one on a chapter, so only an ad-hoc
         * assignment a mentor typed by hand could be rubric scored. That is backwards:
         * the repeatable work every cohort does is exactly the half that benefits from
         * being marked the same way twice.
         */
        private String rubricId;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getBrief() { return brief; }
        public void setBrief(String brief) { this.brief = brief; }
        public int getDueDays() { return dueDays; }
        public void setDueDays(int dueDays) { this.dueDays = dueDays; }
        public int getMarks() { return marks; }
        public void setMarks(int marks) { this.marks = marks; }
        public String getAllow() { return allow; }
        public void setAllow(String allow) { this.allow = allow; }
        public boolean isReview() { return review; }
        public void setReview(boolean review) { this.review = review; }
        public boolean isResubmit() { return resubmit; }
        public void setResubmit(boolean resubmit) { this.resubmit = resubmit; }
        public boolean isLockNext() { return lockNext; }
        public void setLockNext(boolean lockNext) { this.lockNext = lockNext; }
        public List<String> getFileIds() { return fileIds; }
        public void setFileIds(List<String> fileIds) { this.fileIds = fileIds; }
        public String getRubricId() { return rubricId; }
        public void setRubricId(String rubricId) { this.rubricId = rubricId; }
    }

    /** Check my learning: the learner explains it back and the model marks it. */
    public static class TeachBack {
        private boolean enabled = true;
        private String prompt;
        private boolean modelValidates = true;
        private boolean mentorSees = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public boolean isModelValidates() { return modelValidates; }
        public void setModelValidates(boolean modelValidates) { this.modelValidates = modelValidates; }
        public boolean isMentorSees() { return mentorSees; }
        public void setMentorSees(boolean mentorSees) { this.mentorSees = mentorSees; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Test getTest() { return test; }
    public void setTest(Test test) { this.test = test == null ? new Test() : test; }
    public AssignmentSpec getAssignment() { return assignment; }
    public void setAssignment(AssignmentSpec assignment) {
        this.assignment = assignment == null ? new AssignmentSpec() : assignment;
    }
    public TeachBack getTeachback() { return teachback; }
    public void setTeachback(TeachBack teachback) {
        this.teachback = teachback == null ? new TeachBack() : teachback;
    }
}
