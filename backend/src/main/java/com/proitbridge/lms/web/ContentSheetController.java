package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.ContentSheetRun;
import com.proitbridge.lms.domain.ContentSheetVersion;
import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.contentsheet.ContentSheetService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * The master content sheet. Under /api/super/catalogue, so it is open to exactly the
 * roles that may edit the catalogue by hand, and no others.
 */
@RestController
@RequestMapping("/api/super/catalogue/content-sheet")
public class ContentSheetController {

    private final ContentSheetService sheets;
    private final CurrentUser current;

    public ContentSheetController(ContentSheetService sheets, CurrentUser current) {
        this.sheets = sheets;
        this.current = current;
    }

    private ContentSheetService.Actor actor() {
        AuthUser me = current.get();
        return new ContentSheetService.Actor(me.id(), me.email());
    }

    @GetMapping
    public Map<String, Object> status() { return sheets.status(); }

    @PutMapping("/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, Object> body) {
        Object interval = body.get("intervalMinutes");
        return sheets.saveConfig((String) body.get("sheetUrl"),
                body.get("autoSync") == null ? null : Boolean.TRUE.equals(body.get("autoSync")),
                interval instanceof Number n ? n.intValue() : null, actor());
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean preview) {
        return sheets.upload(file, preview, actor());
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestBody(required = false) Map<String, Object> body) {
        boolean preview = body != null && Boolean.TRUE.equals(body.get("preview"));
        return sheets.syncNow(preview, actor());
    }

    @PostMapping("/retry")
    public Map<String, Object> retry() { return sheets.retry(actor()); }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() { return sheets.runList(); }

    @GetMapping("/runs/{id}")
    public ContentSheetRun run(@PathVariable String id) { return sheets.run(id); }

    @GetMapping("/versions")
    public List<ContentSheetVersion> versions() { return sheets.versionList(); }

    @PostMapping("/versions/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id) { return sheets.restore(id, actor()); }

    @GetMapping("/template")
    public ResponseEntity<ByteArrayResource> template() {
        byte[] bytes = sheets.template();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"content-sheet-template.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }
}
