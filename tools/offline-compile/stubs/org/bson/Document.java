package org.bson;
import java.util.*;
public class Document implements Map<String,Object> {
    private final Map<String,Object> m = new LinkedHashMap<>();
    public Document(){}
    public Document(String key, Object value){ m.put(key,value); }
    public Document append(String key, Object value){ m.put(key,value); return this; }
    public String getString(Object key){ return (String) m.get(key); }
    public Integer getInteger(Object key){ return (Integer) m.get(key); }
    public Integer getInteger(Object key, int def){ return def; }
    public Boolean getBoolean(Object key){ return (Boolean) m.get(key); }
    public Boolean getBoolean(Object key, boolean def){ return def; }
    public Long getLong(Object key){ return (Long) m.get(key); }
    public Double getDouble(Object key){ return (Double) m.get(key); }
    public Date getDate(Object key){ return (Date) m.get(key); }
    public org.bson.types.ObjectId getObjectId(Object key){ return (org.bson.types.ObjectId) m.get(key); }
    public <T> T get(Object key, Class<T> clazz){ return clazz.cast(m.get(key)); }
    public List<?> getList(Object key, Class<?> clazz){ return (List<?>) m.get(key); }
    public int size(){return m.size();}
    public boolean isEmpty(){return m.isEmpty();}
    public boolean containsKey(Object k){return m.containsKey(k);}
    public boolean containsValue(Object v){return m.containsValue(v);}
    public Object get(Object k){return m.get(k);}
    public Object put(String k,Object v){return m.put(k,v);}
    public Object remove(Object k){return m.remove(k);}
    public void putAll(Map<? extends String,?> x){m.putAll(x);}
    public void clear(){m.clear();}
    public Set<String> keySet(){return m.keySet();}
    public Collection<Object> values(){return m.values();}
    public Set<Entry<String,Object>> entrySet(){return m.entrySet();}
}
