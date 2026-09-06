package com.mongodb.client;
import com.mongodb.MongoNamespace;
public interface MongoCollection<T> {
    FindIterable<T> find();
    FindIterable<T> find(org.bson.Document filter);
    long countDocuments();
    long countDocuments(org.bson.Document filter);
    void renameCollection(MongoNamespace newNamespace);
    void drop();
    void insertOne(T document);
    void deleteMany(org.bson.Document filter);
}
