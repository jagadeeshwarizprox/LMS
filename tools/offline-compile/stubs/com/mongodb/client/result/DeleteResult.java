package com.mongodb.client.result;
public abstract class DeleteResult {
    public abstract long getDeletedCount();
    public abstract boolean wasAcknowledged();
}
