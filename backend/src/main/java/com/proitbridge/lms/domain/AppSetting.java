package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;
@Document("settings")
public class AppSetting {
    @Id private String key;
    private Object value;

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
}
