package com.fasterxml.jackson.databind;
import java.util.*;
public abstract class JsonNode implements Iterable<JsonNode> {
    public abstract JsonNode path(String field);
    public abstract JsonNode path(int index);
    public abstract JsonNode get(String field);
    public abstract JsonNode get(int index);
    public abstract String asText();
    public abstract String asText(String def);
    public abstract int asInt();
    public abstract int asInt(int def);
    public abstract long asLong();
    public abstract double asDouble();
    public abstract boolean asBoolean();
    public abstract boolean asBoolean(boolean def);
    public abstract boolean isArray();
    public abstract boolean isObject();
    public abstract boolean isMissingNode();
    public abstract boolean isNull();
    public abstract int size();
    public abstract Iterator<String> fieldNames();
    public abstract Iterator<JsonNode> elements();
    public abstract Iterator<JsonNode> iterator();
    public abstract boolean has(String field);
}
