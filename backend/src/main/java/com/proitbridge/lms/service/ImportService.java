package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.Learner;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;

/**
 * Reads ProITbridge_Student_Records.xlsx. Sheet membership decides the track:
 * a row in the premium sheet is a Premium learner, a row in the group sheet is Batch.
 *
 * The source is deliberately behind this one class. When the CRM feed replaces the
 * sheet, only this adapter changes and everything downstream stays as it is.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ProvisioningService provisioning;
    private final String watchDir;
    private final boolean watchDispatchMail;

    public ImportService(ProvisioningService provisioning,
                         @Value("${lms.import.watch-dir:}") String watchDir,
                         @Value("${lms.import.watch-mail:false}") boolean watchDispatchMail) {
        this.provisioning = provisioning;
        this.watchDir = watchDir;
        this.watchDispatchMail = watchDispatchMail;
    }

    private static final List<String> PREMIUM_HINTS = List.of("premium", "personalis", "personaliz");
    private static final List<String> BATCH_HINTS = List.of("group", "batch");

    public record ImportSummary(int created, int upgraded, int existing, int skipped,
                                List<ProvisioningService.Outcome> rows, List<String> sheets) {}

    public ImportSummary importWorkbook(MultipartFile file, boolean dispatchMail, String actorEmail)
            throws Exception {
        try (InputStream in = file.getInputStream()) {
            return read(in, dispatchMail, actorEmail, false);
        }
    }

    /**
     * The same read, committing nothing.
     *
     * Uploading the record sheet was a one way door: press the button and whatever the
     * file happened to contain became accounts. A preview answers the only question
     * anybody actually has first, which is how many of these rows are new.
     */
    public ImportSummary previewWorkbook(MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream()) {
            return read(in, false, "preview", true);
        }
    }

    /**
     * Every sheet with an email column in it is read.
     *
     * The old rule was that a sheet had to be *named* premium or group or batch, and any
     * other name was skipped in silence. A workbook whose tabs were called Sheet1, or
     * Students, or September, imported nothing at all and reported four zeroes with no
     * indication that the file had been ignored rather than found empty. Every column
     * mapping underneath was working; the sheet never reached it.
     *
     * The name is still the best signal when it is there, so it is tried first. Failing
     * that a track column on the row decides, and failing that the sheet is read as batch,
     * which is the larger and less privileged of the two: an account created with too
     * little is fixed by an upgrade, and provisioning already upgrades in place without
     * duplicating anyone. What is never done again is dropping the rows and saying nothing.
     *
     * Each sheet reports what happened to it either way, so a zero is always explained.
     */
    private ImportSummary read(InputStream in, boolean dispatchMail, String actorEmail,
                               boolean dryRun) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in)) {
            List<ProvisioningService.Outcome> outcomes = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String name = sheet.getSheetName();

                if (findHeader(sheet) == null) {
                    notes.add(name + ": no email column, so nothing here is a learner.");
                    continue;
                }

                Learner.TrackType named = trackFor(name);
                List<ProvisioningService.Outcome> got =
                        readSheet(sheet, named, dispatchMail, actorEmail, dryRun);
                outcomes.addAll(got);

                notes.add(name + ": " + got.size() + " row" + (got.size() == 1 ? "" : "s")
                        + " read as " + (named == null
                            ? "premium or batch per row, since the tab name does not say"
                            : named.name().toLowerCase()) + ".");
            }
            if (notes.isEmpty()) notes.add("This workbook has no sheets in it.");
            return summarise(outcomes, notes);
        }
    }

    /**
     * The watched folder.
     *
     * Off unless a directory is configured, because a poller that finds nothing is a
     * log line every few minutes forever. When it is on, a sheet dropped in that folder
     * is read on the schedule and new rows become accounts in the batch the sheet names.
     * Provisioning is idempotent on email, so re-reading the same file every cycle is
     * safe and costs one pass: rows already provisioned come back as EXISTING and
     * nothing is written.
     */
    @Scheduled(fixedDelayString = "${lms.import.watch-seconds:300}000")
    public void sweepWatchedFolder() {
        if (watchDir == null || watchDir.isBlank()) return;
        Path dir = Paths.get(watchDir);
        if (!Files.isDirectory(dir)) {
            log.warn("import watch: {} is not a directory, nothing will be read", watchDir);
            return;
        }
        try (var listing = Files.list(dir)) {
            for (Path f : listing.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase();
                if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) continue;
                try (InputStream in = Files.newInputStream(f)) {
                    ImportSummary sum = read(in, watchDispatchMail, "watched folder", false);
                    if (sum.created() > 0 || sum.upgraded() > 0) {
                        log.info("import watch: {} created {}, upgraded {} from {}",
                                name, sum.created(), sum.upgraded(), watchDir);
                    }
                } catch (Exception e) {
                    log.warn("import watch: could not read {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("import watch: could not list {}: {}", watchDir, e.getMessage());
        }
    }

    private Learner.TrackType trackFor(String sheetName) {
        String n = sheetName.toLowerCase();
        if (PREMIUM_HINTS.stream().anyMatch(n::contains)) return Learner.TrackType.PREMIUM;
        if (BATCH_HINTS.stream().anyMatch(n::contains)) return Learner.TrackType.BATCH;
        return null;
    }

    /**
     * Which track a single row belongs to, when the tab name did not say.
     *
     * A column called track, type, plan or programme is read for the word premium or
     * personalised. Nothing there means batch.
     */
    private Learner.TrackType trackForRow(Row row, Map<String, Integer> cols) {
        String said = pick(row, cols, "track", "type", "plan", "programme", "program", "category");
        if (said == null) return Learner.TrackType.BATCH;
        String n = said.toLowerCase();
        if (PREMIUM_HINTS.stream().anyMatch(n::contains)) return Learner.TrackType.PREMIUM;
        return Learner.TrackType.BATCH;
    }

    private List<ProvisioningService.Outcome> readSheet(Sheet sheet, Learner.TrackType track,
                                                        boolean dispatchMail, String actorEmail,
                                                        boolean dryRun) {
        List<ProvisioningService.Outcome> out = new ArrayList<>();
        Row header = findHeader(sheet);
        if (header == null) return out;
        Map<String, Integer> cols = new HashMap<>();
        for (Cell c : header) {
            String v = text(c).toLowerCase().trim();
            if (!v.isEmpty()) cols.put(v, c.getColumnIndex());
        }
        for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String email = pick(row, cols, "email", "email id", "mail", "email address");
            if (email == null || email.isBlank()) continue;
            var record = new ProvisioningService.RecordRow(
                    pick(row, cols, "name", "full name", "student name", "learner name"),
                    email,
                    pick(row, cols, "phone", "contact", "mobile", "contact number"),
                    pick(row, cols, "whatsapp", "whatsapp number"),
                    pick(row, cols, "course", "bundle", "course opted", "programme", "program"),
                    track == null ? trackForRow(row, cols) : track,
                    pick(row, cols, "batch", "batch no", "batch number", "batch code"),
                    date(row, cols, "date of joining", "joining date", "doj", "enrolled on"),
                    sheet.getSheetName() + "!" + (r + 1));
            out.add(dryRun
                    ? provisioning.wouldProvision(record)
                    : provisioning.provision(record, dispatchMail, actorEmail));
        }
        return out;
    }

    /** The sheets carry a title block above the header, so find the row holding "email". */
    private Row findHeader(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 25); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                if (text(c).toLowerCase().contains("email")) return row;
            }
        }
        return null;
    }

    private String pick(Row row, Map<String, Integer> cols, String... names) {
        for (String n : names) {
            Integer idx = cols.get(n);
            if (idx == null) {
                idx = cols.entrySet().stream()
                        .filter(e -> e.getKey().contains(n))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
            if (idx != null) {
                String v = text(row.getCell(idx));
                if (!v.isBlank()) return v.trim();
            }
        }
        return null;
    }

    private LocalDate date(Row row, Map<String, Integer> cols, String... names) {
        for (String n : names) {
            Integer idx = cols.get(n);
            if (idx == null) continue;
            Cell c = row.getCell(idx);
            if (c != null && c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                return c.getLocalDateTimeCellValue().toLocalDate();
            }
        }
        return null;
    }

    private String text(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(c)
                    ? c.getLocalDateTimeCellValue().toLocalDate().toString()
                    : String.valueOf((long) c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> c.getCellFormula();
            default -> "";
        };
    }

    private ImportSummary summarise(List<ProvisioningService.Outcome> rows, List<String> sheets) {
        int created = 0, upgraded = 0, existing = 0, skipped = 0;
        for (var o : rows) {
            switch (o.status()) {
                case "CREATED" -> created++;
                case "UPGRADED" -> upgraded++;
                case "EXISTS" -> existing++;
                default -> skipped++;
            }
        }
        return new ImportSummary(created, upgraded, existing, skipped, rows, sheets);
    }
}
