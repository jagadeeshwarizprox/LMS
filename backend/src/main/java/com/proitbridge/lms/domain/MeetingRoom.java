package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/**
 * A standing meeting room. The room is always open; slots schedule when it is used.
 * The URL lives here only, so it is never duplicated across surfaces or pages.
 */
@Document("meeting_rooms")
public class MeetingRoom {
    @Id private String id;
    private String ownerId;              // mentor
    private String label;
    private String provider = "ZOOM";
    private String joinUrl;
    private String passcode;
    private boolean active = true;
    /*
     * A room made for one session's own link, as opposed to the mentor's standing room.
     * Rows written before this flag existed read as false, so they stay standing rooms.
     * Without the distinction, a link changed on one session was written over the
     * standing room and moved every session the mentor runs.
     */
    private boolean pinned;
    private String pinnedSlotId;

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getJoinUrl() { return joinUrl; }
    public void setJoinUrl(String joinUrl) { this.joinUrl = joinUrl; }
    public String getPasscode() { return passcode; }
    public void setPasscode(String passcode) { this.passcode = passcode; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public String getPinnedSlotId() { return pinnedSlotId; }
    public void setPinnedSlotId(String pinnedSlotId) { this.pinnedSlotId = pinnedSlotId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
