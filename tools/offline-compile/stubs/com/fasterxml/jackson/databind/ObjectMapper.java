package com.fasterxml.jackson.databind;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.*;
public class ObjectMapper {
    public String writeValueAsString(Object v) throws JsonProcessingException {return null;}
    public byte[] writeValueAsBytes(Object v) throws JsonProcessingException {return null;}
    public JsonNode readTree(String content) throws JsonProcessingException {return null;}
    public JsonNode readTree(byte[] content) throws IOException {return null;}
    public JsonNode readTree(InputStream in) throws IOException {return null;}
    public <T> T readValue(String content, Class<T> type) throws JsonProcessingException {return null;}
    public <T> T convertValue(Object from, Class<T> type){return null;}
    public ObjectMapper findAndRegisterModules(){return this;}
}
