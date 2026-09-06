package org.bson.types;
public class ObjectId {
    public ObjectId(){}
    public ObjectId(String hex){}
    public static boolean isValid(String hex){return true;}
    public String toHexString(){return "";}
}
