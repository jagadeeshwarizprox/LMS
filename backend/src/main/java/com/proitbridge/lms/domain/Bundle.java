package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.util.*;

/**
 * A course: an ordered bundle of modules, priced, with its own access rules.
 *
 * The same module sits in more than one course, which is the whole point of bundling
 * rather than copying. Python is one module and it is in Data Analyst, Data Scientist
 * and Agentic AI; editing it once fixes it everywhere.
 */
@Document("bundles")
public class Bundle {

    @Id private String id;
    @Indexed(unique = true) private String name;
    private String description;
    private double price;

    /** The order they open in. */
    private List<String> moduleIds = new ArrayList<>();

    /** Shown on the course card, above the name. */
    private String tag;
    /** Cover colour, used when no image is uploaded. */
    private String color = "#0a1f44";
    private String imageFileId;

    /**
     * Draft until somebody publishes it. Separate from {@code active}, which is what
     * archiving sets when a course has learners on it and cannot be deleted.
     */
    private boolean published;
    private boolean active = true;

    private Access access = new Access();

    /** Who a course is open to, and how it opens. */
    public static class Access {
        private boolean premium = true;
        private boolean batch = true;
        private boolean selfPaced;
        /** A chapter opens only after the one before it is cleared. */
        private boolean sequential = true;
        private boolean certificate = true;

        public boolean isPremium() { return premium; }
        public void setPremium(boolean premium) { this.premium = premium; }
        public boolean isBatch() { return batch; }
        public void setBatch(boolean batch) { this.batch = batch; }
        public boolean isSelfPaced() { return selfPaced; }
        public void setSelfPaced(boolean selfPaced) { this.selfPaced = selfPaced; }
        public boolean isSequential() { return sequential; }
        public void setSequential(boolean sequential) { this.sequential = sequential; }
        public boolean isCertificate() { return certificate; }
        public void setCertificate(boolean certificate) { this.certificate = certificate; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }
    public List<String> getModuleIds() { return moduleIds; }
    public void setModuleIds(List<String> moduleIds) { this.moduleIds = moduleIds; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getImageFileId() { return imageFileId; }
    public void setImageFileId(String imageFileId) { this.imageFileId = imageFileId; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Access getAccess() { return access; }
    public void setAccess(Access access) { this.access = access; }
}
