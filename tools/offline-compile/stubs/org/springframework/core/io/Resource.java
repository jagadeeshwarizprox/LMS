package org.springframework.core.io;
import java.io.*;
public interface Resource { long contentLength() throws IOException; InputStream getInputStream() throws IOException; String getFilename(); boolean exists(); }
