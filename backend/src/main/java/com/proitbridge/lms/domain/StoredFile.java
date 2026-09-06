package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/**
 * An uploaded file. Bytes live on disk under a storage key the client never sees;
 * everything refers to this document, so a download is always an authorised read.
 */
@Document("files")
public class StoredFile {
    @Id private String id;
    private String filename;
    private String contentType;
    private long sizeBytes;
    private String storageKey;        // opaque, never leaves the server
    private String ownerId;
    private String purpose;           // TASK|PROJECT|RESUME|RECORDING_NOTE
    private String linkedId;
    private Instant uploadedAt = Instant.now();

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getLinkedId() { return linkedId; }
    public void setLinkedId(String linkedId) { this.linkedId = linkedId; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
