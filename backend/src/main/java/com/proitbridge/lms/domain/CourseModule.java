package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.util.*;
@Document("modules")
public class CourseModule {
    @Id private String id;
    @Indexed(unique = true) private String slug;
    /** M01, M02. Short enough to sit in a table cell and be said out loud. */
    private String code;
    private String name;
    private String description;
    private int position;
    private boolean active = true;

    /*
     * Only a Bundle carried a publish flag, so a module half written inside a published
     * course was live the moment its first chapter existed: the super admin's drafts
     * were on the learner's roadmap, which is exactly what the review reported.
     *
     * It defaults true so that everything already in the catalogue stays where it is.
     * A module created from now on is written as a draft by whoever creates it.
     */
    private boolean published = true;

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
}
