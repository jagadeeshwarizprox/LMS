package org.springframework.core.io;
import java.io.*;
import java.nio.file.*;
public class FileSystemResource implements Resource {
    private final Path path;
    public FileSystemResource(Path path){this.path=path;}
    public FileSystemResource(String path){this.path=Paths.get(path);}
    public long contentLength() throws IOException {return Files.size(path);}
    public InputStream getInputStream() throws IOException {return Files.newInputStream(path);}
    public String getFilename(){return path.getFileName().toString();}
    public boolean exists(){return Files.exists(path);}
}
