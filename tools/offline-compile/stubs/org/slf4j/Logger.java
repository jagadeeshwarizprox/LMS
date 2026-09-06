package org.slf4j;
public interface Logger {
    void trace(String m); void trace(String f,Object... a);
    void debug(String m); void debug(String f,Object... a);
    void info(String m);  void info(String f,Object... a);
    void warn(String m);  void warn(String f,Object... a); void warn(String m,Throwable t);
    void error(String m); void error(String f,Object... a); void error(String m,Throwable t);
    boolean isDebugEnabled();
}
