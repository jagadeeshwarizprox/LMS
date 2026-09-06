package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * A project the organisation teaches, authored once and run by every batch.
 *
 * What existed before this was a portfolio form: a learner typed the title of something
 * they had built elsewhere and a mentor approved it. That is worth keeping, and it still
 * is, but it cannot be taught against. There is no brief, no data, no checkpoint, and
 * nothing for a Saturday session to be about.
 *
 * The stages are fixed for every project rather than authored per project. A mentor
 * learns one flow and a batch grid compares across projects; per-project stages would be
 * more flexible and considerably harder to run a cohort against.
 */
@Document("project_catalogue")
public class Project {

    @Id private String id;

    private String title;
    private String subtitle;

    /** The problem, as a client would put it. Markdown. */
    private String brief;
    /** Who the work is for, so the learner has somebody to write for. */
    private String clientContext;
    /** What "finished" means, in the author's words rather than the learner's. */
    private String successCriteria;

    /** Files handed to the learner: data, a starter repo, a spec. */
    private List<String> resourceFileIds = new ArrayList<>();
    /** Anything that lives elsewhere: a dataset link, an API console. */
    private List<Link> resourceLinks = new ArrayList<>();

    /** Bundles this project can be attached to. Empty means any. */
    private List<String> bundleIds = new ArrayList<>();

    /** PREMIUM | BATCH | BOTH */
    private String trackScope = "BOTH";

    /** BEGINNER | INTERMEDIATE | ADVANCED */
    private String difficulty = "INTERMEDIATE";

    private int expectedHours;

    /**
     * Teams are supported and default to off.
     *
     * A team of four hiding one person who did nothing is the commonest failure in
     * cohort projects, so it stays off until a mentor can see per-person contribution.
     */
    private boolean teamAllowed;
    private int teamSize = 1;

    private List<Stage> stages = new ArrayList<>();

    private boolean active = true;

    /** The six stages, seeded on creation so no project is authored empty. */
    public static List<Stage> defaultStages() {
        List<Stage> out = new ArrayList<>();
        out.add(new Stage("BRIEF", "Brief and kickoff",
                "Read the brief, get access to the data, and join the kickoff session.",
                "", 0, false));
        out.add(new Stage("SCOPE", "Scope and approach",
                "A short note: what you will build, what you are deliberately leaving out, "
                        + "and how you will know it worked.",
                "One page is plenty. This is the stage that saves the other five.", 5, true));
        out.add(new Stage("BUILD_1", "Checkpoint one: foundation",
                "Data in and cleaned, environment running, the skeleton of the thing working end to end.",
                "The unglamorous half. Nothing after this is fixable if this is wrong.", 12, true));
        out.add(new Stage("BUILD_2", "Checkpoint two: core build",
                "The deliverable itself.",
                "", 21, true));
        out.add(new Stage("REVIEW", "Review and rework",
                "Respond to the review. Nothing new is built here.",
                "", 26, true));
        out.add(new Stage("DEMO", "Demo and handover",
                "A recorded walkthrough, a README somebody else could follow, and the repository.",
                "Record it as if the client is watching, because in an interview somebody will be.",
                30, true));
        return out;
    }

    /** One step of the run, with what it wants and when it is due. */
    public static class Stage {
        private String key;
        private String name;
        private String asks;
        private String guidance;
        /** Days from enrolment, so the same project runs for every batch unedited. */
        private int dueOffsetDays;
        /** A stage the learner submits to. BRIEF is not one. */
        private boolean submittable = true;
        private String rubricId;
        private List<String> acceptedExtensions = new ArrayList<>();

        public Stage() { }

        public Stage(String key, String name, String asks, String guidance,
                     int dueOffsetDays, boolean submittable) {
            this.key = key; this.name = name; this.asks = asks;
            this.guidance = guidance; this.dueOffsetDays = dueOffsetDays;
            this.submittable = submittable;
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAsks() { return asks; }
        public void setAsks(String asks) { this.asks = asks; }
        public String getGuidance() { return guidance; }
        public void setGuidance(String guidance) { this.guidance = guidance; }
        public int getDueOffsetDays() { return dueOffsetDays; }
        public void setDueOffsetDays(int dueOffsetDays) { this.dueOffsetDays = dueOffsetDays; }
        public boolean isSubmittable() { return submittable; }
        public void setSubmittable(boolean submittable) { this.submittable = submittable; }
        public String getRubricId() { return rubricId; }
        public void setRubricId(String rubricId) { this.rubricId = rubricId; }
        public List<String> getAcceptedExtensions() { return acceptedExtensions; }
        public void setAcceptedExtensions(List<String> acceptedExtensions) {
            this.acceptedExtensions = acceptedExtensions == null ? new ArrayList<>() : acceptedExtensions;
        }
    }

    public static class Link {
        private String label;
        private String url;

        public Link() { }
        public Link(String label, String url) { this.label = label; this.url = url; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getBrief() { return brief; }
    public void setBrief(String brief) { this.brief = brief; }
    public String getClientContext() { return clientContext; }
    public void setClientContext(String clientContext) { this.clientContext = clientContext; }
    public String getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(String successCriteria) { this.successCriteria = successCriteria; }
    public List<String> getResourceFileIds() { return resourceFileIds; }
    public void setResourceFileIds(List<String> resourceFileIds) {
        this.resourceFileIds = resourceFileIds == null ? new ArrayList<>() : resourceFileIds;
    }
    public List<Link> getResourceLinks() { return resourceLinks; }
    public void setResourceLinks(List<Link> resourceLinks) {
        this.resourceLinks = resourceLinks == null ? new ArrayList<>() : resourceLinks;
    }
    public List<String> getBundleIds() { return bundleIds; }
    public void setBundleIds(List<String> bundleIds) {
        this.bundleIds = bundleIds == null ? new ArrayList<>() : bundleIds;
    }
    public String getTrackScope() { return trackScope; }
    public void setTrackScope(String trackScope) { this.trackScope = trackScope; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public int getExpectedHours() { return expectedHours; }
    public void setExpectedHours(int expectedHours) { this.expectedHours = expectedHours; }
    public boolean isTeamAllowed() { return teamAllowed; }
    public void setTeamAllowed(boolean teamAllowed) { this.teamAllowed = teamAllowed; }
    public int getTeamSize() { return teamSize; }
    public void setTeamSize(int teamSize) { this.teamSize = teamSize; }
    public List<Stage> getStages() { return stages; }
    public void setStages(List<Stage> stages) {
        this.stages = stages == null ? new ArrayList<>() : stages;
    }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
