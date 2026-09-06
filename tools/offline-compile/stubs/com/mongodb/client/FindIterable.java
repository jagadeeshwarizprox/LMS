package com.mongodb.client;
public interface FindIterable<T> extends Iterable<T> {
    T first();
    FindIterable<T> limit(int limit);
    FindIterable<T> skip(int skip);
    FindIterable<T> sort(org.bson.Document sort);
    FindIterable<T> projection(org.bson.Document projection);
}
