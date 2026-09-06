package com.mongodb.client;
public interface MongoDatabase {
    String getName();
    MongoCollection<org.bson.Document> getCollection(String name);
    <T> MongoCollection<T> getCollection(String name, Class<T> documentClass);
    void drop();
    Iterable<String> listCollectionNames();
}
