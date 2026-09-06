package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Named criteria, so two mentors score the same piece of work the same way.
 *
 * The weights are what make a rubric more than a checklist: "does it work" counting
 * three times "is it readable" is a statement about what the course values, and it
 * belongs in the data rather than in each mentor's head.
 */
@Document("rubrics")
public class Rubric {

    @Id
    private String id;

    private String name;

    /** TASK | PROJECT | MOCK */
    private String scope = "TASK";

    private List<Criterion> criteria = new ArrayList<>();

    private boolean active = true;

    public Rubric() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public List<Criterion> getCriteria() {
        return criteria;
    }

    public void setCriteria(List<Criterion> criteria) {
        this.criteria = criteria;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * One thing being scored, with a weight and a line of guidance.
     *
     * The guidance is not decoration. Without it, "clarity" means whatever the mentor
     * reading it that afternoon decides it means, which is the problem a rubric exists
     * to solve.
     */
    public static class Criterion {

        private String key;
        private String label;
        private String guidance;
        private int weight = 1;

        public Criterion() {
        }

        public Criterion(String key, String label, String guidance, int weight) {
            this.key = key;
            this.label = label;
            this.guidance = guidance;
            this.weight = weight;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getGuidance() {
            return guidance;
        }

        public void setGuidance(String guidance) {
            this.guidance = guidance;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }
    }
}
