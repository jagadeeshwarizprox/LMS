package com.proitbridge.lms.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;

/**
 * Files on the machine running the API. The default, and the right answer while there is
 * one server and somebody is backing it up.
 *
 * What it does not survive: a container restart on ephemeral storage, or a second
 * instance behind a load balancer, since the other instance cannot see these bytes.
 * Both are reasons to move, not reasons to have started somewhere else.
 */
@Component
@ConditionalOnProperty(name = "lms.files.provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalDiskStorage implements StorageProvider {

    private final Path root;

    public LocalDiskStorage(@Value("${lms.files.dir}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the upload directory at " + root, e);
        }
    }

    @Override
    public String key() { return "LOCAL"; }

    @Override
    public void put(String storageKey, InputStream data, long sizeBytes, String contentType) {
        try {
            Path target = resolve(storageKey);
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The upload failed.");
        }
    }

    @Override
    public byte[] get(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.GONE, "That file is no longer on disk.");
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException ignored) {
            /* the record is what matters; an orphaned byte on disk is not worth failing on */
        }
    }

    /** The key is generated, but a path that escapes the directory is still worth refusing. */
    private Path resolve(String storageKey) {
        Path p = root.resolve(storageKey).normalize();
        if (!p.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad storage key.");
        }
        return p;
    }
}
