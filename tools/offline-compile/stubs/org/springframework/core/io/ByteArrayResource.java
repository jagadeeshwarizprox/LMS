package org.springframework.core.io;
import java.io.*;
public class ByteArrayResource implements Resource {
    private final byte[] bytes; private final String desc;
    public ByteArrayResource(byte[] bytes){this(bytes,null);}
    public ByteArrayResource(byte[] bytes,String desc){this.bytes=bytes;this.desc=desc;}
    public byte[] getByteArray(){return bytes;}
    public long contentLength(){return bytes.length;}
    public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
    public String getFilename(){return desc;}
    public boolean exists(){return true;}
}
