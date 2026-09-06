package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.StoredFile;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.FileService;
import com.proitbridge.lms.service.LearnerService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Uploads and downloads. Both sides are authenticated; nothing is served publicly. */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService files;
    private final CurrentUser current;
    private final LearnerService learners;

    public FileController(FileService files, CurrentUser current, LearnerService learners) {
        this.files = files;
        this.current = current;
        this.learners = learners;
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(defaultValue = "TASK") String purpose,
                                      @RequestParam(required = false) String linkedId) {
        StoredFile f = files.store(file, current.get().id(), purpose, linkedId);
        return Map.of("id", f.getId(), "filename", f.getFilename(),
                "sizeBytes", f.getSizeBytes(), "contentType", f.getContentType());
    }

    /**
     * Two kinds of file, two rules.
     *
     * Being signed in used to be the whole check, which is not a check at all: a file id
     * read off a task thread downloaded another learner's notebook. That was replaced by
     * "a learner may read what they own", which fixed the leak and broke the other half
     * of the job, because **course material is owned by the staff member who uploaded
     * it**. Every assignment brief, dataset and rubric returned 403 to every learner, on
     * every chapter, from the day that guard was written. The buttons rendered and none
     * of them worked.
     *
     * The two kinds are genuinely different:
     *
     *   Material  (ASSIGNMENT, RESOURCE) belongs to a chapter. Readable by any learner
     *             entitled to that chapter, and by nobody else who happens to be signed
     *             in, because a paid library is worth protecting.
     *
     *   Submissions (TASK, RESUME, PROJECT) belong to a person. The owner and staff.
     *
     * Staff still get everything, because reviewing work is the job.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id) {
        StoredFile f = files.meta(id);
        var me = current.get();
        if ("LEARNER".equals(me.role()) && !me.id().equals(f.getOwnerId())) {
            boolean material = "ASSIGNMENT".equals(f.getPurpose()) || "RESOURCE".equals(f.getPurpose());
            if (!material || !learners.canOpenChapter(me.id(), f.getLinkedId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That file is not yours.");
            }
        }
        byte[] bytes = files.read(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + f.getFilename().replace("\"", "") + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }
}
