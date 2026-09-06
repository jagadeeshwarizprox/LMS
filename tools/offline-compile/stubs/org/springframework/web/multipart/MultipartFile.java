package org.springframework.web.multipart;
import java.io.*;
public interface MultipartFile {
    String getName();
    String getOriginalFilename();
    String getContentType();
    boolean isEmpty();
    long getSize();
    byte[] getBytes() throws IOException;
    InputStream getInputStream() throws IOException;
    void transferTo(File dest) throws IOException, IllegalStateException;
    default void transferTo(java.nio.file.Path dest) throws IOException, IllegalStateException {}
}
