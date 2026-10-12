package com.mongodb.client.result;
public abstract class UpdateResult {
    public abstract boolean wasAcknowledged();
    public abstract long getMatchedCount();
    public abstract long getModifiedCount();
}
