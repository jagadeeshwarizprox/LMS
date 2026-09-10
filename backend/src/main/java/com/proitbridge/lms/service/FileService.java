package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.StoredFile;
import com.proitbridge.lms.repo.StoredFileRepository;
import com.proitbridge.lms.service.storage.StorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Real uploads, replacing links to somebody's Drive.
 *
 * The stored name is a random key, never the filename, so nothing is guessable and a
 * traversal in a filename cannot escape the directory. Type and size are checked on the
 * way in, and every download goes through an authorised read rather than a public path.
 */
@Service
public class FileService {

    /*
     * Extensions, not MIME types. A browser sends text/x-python for a .py on one
     * machine and application/octet-stream on the next, so a MIME whitelist rejected
     * the notebooks and scripts this product exists to collect, while an assignment
     * spec sat there promising "pdf, ipynb, py, zip". The extension is what a person
     * chose and what the viewer keys off, so it is what to check.
     */
    private static final Set<String> ALLOWED_EXT = Set.of(
            // documents
            "pdf", "doc", "docx", "odt", "rtf", "txt", "md", "html",
            // data and sheets
            "csv", "tsv", "xls", "xlsx", "ods", "json", "xml", "yaml", "yml", "parquet",
            // notebooks and code
            "ipynb", "py", "r", "rmd", "sql", "js", "ts", "java", "c", "cpp", "cs",
            "go", "rb", "php", "sh", "css", "scala", "m", "jl",
            // slides
            "ppt", "pptx", "odp",
            /*
             * Business intelligence. A Power BI or Tableau workbook is the deliverable on
             * half the tasks this product sets, and the list refused all of them: a learner
             * finishing a dashboard exercise had nothing they were allowed to hand in.
             */
            "pbix", "pbit", "twb", "twbx", "qvf",
            // images
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "tif", "tiff",
            // archives
            "zip", "tar", "gz", "tgz", "7z", "rar");

    /*
     * Refused whatever the extension says, because these are the ones that do damage
     * if a person downloads and opens them without thinking. A learner has no reason
     * to hand one in, so there is nothing lost by the rule being blunt.
     */
    private static final Set<String> DENIED_EXT = Set.of(
            "exe", "msi", "bat", "cmd", "com", "scr", "pif", "cpl", "jar", "app",
            "dmg", "pkg", "deb", "rpm", "apk", "dll", "so", "dylib", "vbs", "ps1",
            "reg", "lnk", "iso");

    private final StoredFileRepository files;
    private final StorageProvider storage;
    private final long maxBytes;

    public FileService(StoredFileRepository files, StorageProvider storage,
                       @Value("${lms.files.max-bytes}") long maxBytes) {
        this.files = files;
        this.storage = storage;
        this.maxBytes = maxBytes;
    }

    public StoredFile store(MultipartFile upload, String ownerId, String purpose, String linkedId) {
        if (upload == null || upload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was attached.");
        }
        if (upload.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        String type = upload.getContentType() == null ? "" : upload.getContentType();
        String ext = extensionOf(upload.getOriginalFilename());
        if (ext.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "That file has no extension, so there is no way to tell what it is.");
        }
        if (DENIED_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "A ." + ext + " file is a program, and those are never accepted here.");
        }
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "A ." + ext + " file is not accepted. Notebooks, scripts, documents, "
                    + "sheets, slides, images and archives are.");
        }

        /* the key is random and generated here: never the filename, never guessable,
           and never anything a caller can influence */
        String key = UUID.randomUUID().toString().replace("-", "");
        try (var in = upload.getInputStream()) {
            storage.put(key, in, upload.getSize(), type);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The upload failed.");
        }

        StoredFile f = new StoredFile();
        f.setFilename(cleanName(upload.getOriginalFilename()));
        f.setContentType(type);
        f.setSizeBytes(upload.getSize());
        f.setStorageKey(key);
        f.setOwnerId(ownerId);
        f.setPurpose(purpose);
        f.setLinkedId(linkedId);
        return files.save(f);
    }

    public StoredFile meta(String id) {
        return files.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file."));
    }

    public byte[] read(String id) {
        return storage.get(meta(id).getStorageKey());
    }

    /**
     * Object storage can hand the browser a short lived URL, which takes a large
     * download off the API entirely. A disk cannot, so the caller streams instead.
     */
    public String directUrl(String id, int seconds) {
        StoredFile f = meta(id);
        return storage.supportsDirectUrl()
                ? storage.directUrl(f.getStorageKey(), f.getFilename(), seconds) : null;
    }

    public void delete(String id) {
        StoredFile f = meta(id);
        storage.delete(f.getStorageKey());
        files.delete(f);
    }

    public String provider() { return storage.key(); }

    public List<StoredFile> forLinked(String linkedId) { return files.findByLinkedId(linkedId); }

    /**
     * A file as a screen needs it: what it is called and how big it is.
     *
     * File ids were sent to the browser bare, so three attachments rendered as three
     * identical buttons saying "Download" and the chapter editor listed `a3f91c02`
     * instead of `rubric.pdf`. One helper, used by the learner's brief, the chapter
     * editor and the mentor's review thread, so those three cannot drift apart.
     *
     * A missing file yields a row saying so rather than vanishing: an attachment that
     * disappears silently looks like a bug in the page, and somebody should know the
     * upload is gone.
     */
    public List<Map<String, Object>> viewsOf(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            files.findById(id).ifPresentOrElse(f -> {
                m.put("filename", f.getFilename());
                m.put("sizeBytes", f.getSizeBytes());
                m.put("contentType", f.getContentType());
                String n = f.getFilename() == null ? "" : f.getFilename();
                int dot = n.lastIndexOf('.');
                m.put("ext", dot > 0 ? n.substring(dot + 1).toLowerCase() : "");
                m.put("missing", false);
            }, () -> {
                m.put("filename", "This file is no longer available");
                m.put("sizeBytes", 0L);
                m.put("ext", "");
                m.put("missing", true);
            });
            out.add(m);
        }
        return out;
    }

    /** The original name is only ever shown, never used as a path. */
    private String cleanName(String name) {
        if (name == null || name.isBlank()) return "upload";
        String base = Paths.get(name).getFileName().toString();
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    /** Lower case, no dot, empty when there is not one. Read off the cleaned name so a
     *  path in the filename cannot smuggle a different extension past the check. */
    private String extensionOf(String name) {
        String base = cleanName(name);
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return "";
        return base.substring(dot + 1).toLowerCase();
    }
}
