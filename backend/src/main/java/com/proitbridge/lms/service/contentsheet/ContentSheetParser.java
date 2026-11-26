package com.proitbridge.lms.service.contentsheet;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Reading the master content sheet.
 *
 * Every tab whose first rows hold the template's header is read, so one workbook can
 * carry several courses on several tabs, or all of them on one. Headers are matched
 * loosely ("Name of module", "Module", "module name" are the same column) because a
 * sheet people maintain by hand drifts; the three columns that identify a row are the
 * only ones that must be there.
 *
 * A cell that shows text but carries a hyperlink is read as the hyperlink, which is
 * what Sheets does when someone pastes a link over a label.
 */
public final class ContentSheetParser {

    private ContentSheetParser() {}

    public static final String SNO = "SNO", COURSE = "COURSE", MODULE = "MODULE", CHAPTER = "CHAPTER", TITLE = "TITLE",
            ONELINE = "ONELINE", RUNNER = "RUNNER", VIDEO = "VIDEO", NOTES = "NOTES", CODE = "CODE",
            DATASET = "DATASET", SLIDES = "SLIDES", WRITTEN = "WRITTEN";

    /**
     * The template's own headers, in the template's order. Name of course is optional:
     * left out or left blank, the module is synced on its own and its courses are
     * arranged by hand as before.
     */
    public static final List<String> TEMPLATE_HEADERS = List.of(
            "S.no.", "Name of course", "Name of module", "Chapter name", "Title", "One line",
            "Code runner(python/none)", "Video link", "Notes", "Code", "Dataset", "Slides",
            "Written note");

    public record SheetRow(String sheet, int rowNumber, int order, Map<String, String> cells) {
        public String get(String column) { return cells.getOrDefault(column, ""); }
        public String where() { return sheet + " row " + rowNumber; }
    }

    public record Parsed(List<SheetRow> rows, List<String> problems, List<String> sheetsRead) {}

    static String column(String header) {
        String n = header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty()) return null;
        if (n.startsWith("sno") || n.startsWith("serial") || n.equals("slno") || n.equals("no")) return SNO;
        if (n.equals("nameofcourse") || n.equals("course") || n.equals("coursename")
                || n.equals("nameofthecourse")) return COURSE;
        if (n.equals("nameofmodule") || n.equals("module") || n.equals("modulename")
                || n.equals("nameofthemodule")) return MODULE;
        if (n.equals("chaptername") || n.equals("chapter") || n.equals("chaptertitle")) return CHAPTER;
        if (n.equals("title") || n.equals("topic") || n.equals("topictitle") || n.equals("topicname")) return TITLE;
        if (n.equals("oneline") || n.equals("summary")) return ONELINE;
        if (n.startsWith("coderunner") || n.equals("runner")) return RUNNER;
        if (n.startsWith("video")) return VIDEO;
        if (n.equals("notes") || n.equals("note")) return NOTES;
        if (n.equals("code")) return CODE;
        if (n.startsWith("dataset") || n.equals("data")) return DATASET;
        if (n.startsWith("slide")) return SLIDES;
        if (n.startsWith("writtennote") || n.equals("written")) return WRITTEN;
        return null;
    }

    public static Parsed parse(byte[] workbook) throws IOException {
        List<SheetRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> sheetsRead = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(workbook))) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            int order = 0;
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                Map<Integer, String> columns = null;
                int headerRow = -1;
                for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 10); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Map<Integer, String> found = new LinkedHashMap<>();
                    for (Cell c : row) {
                        String col = column(fmt.formatCellValue(c, eval));
                        if (col != null && !found.containsValue(col)) found.put(c.getColumnIndex(), col);
                    }
                    if (found.containsValue(MODULE) && found.containsValue(CHAPTER) && found.containsValue(TITLE)) {
                        columns = found;
                        headerRow = r;
                        break;
                    }
                }
                if (columns == null) continue;     // not a content tab: instructions, notes, anything else
                sheetsRead.add(sheet.getSheetName());

                for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Map<String, String> cells = new HashMap<>();
                    boolean any = false;
                    for (Map.Entry<Integer, String> e : columns.entrySet()) {
                        Cell c = row.getCell(e.getKey());
                        String v = valueOf(c, fmt, eval);
                        if (!v.isEmpty()) any = true;
                        cells.put(e.getValue(), v);
                    }
                    if (!any) continue;
                    rows.add(new SheetRow(sheet.getSheetName(), r + 1, order++, cells));
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("That file could not be read as an .xlsx workbook.", e);
        }
        if (sheetsRead.isEmpty()) {
            problems.add("No tab has the template's header row. The first rows of a tab must include "
                    + "Name of module, Chapter name and Title.");
        }
        return new Parsed(rows, problems, sheetsRead);
    }

    private static String valueOf(Cell c, DataFormatter fmt, FormulaEvaluator eval) {
        if (c == null) return "";
        String text = fmt.formatCellValue(c, eval);
        text = text == null ? "" : text.trim();
        Hyperlink link = c.getHyperlink();
        if (link != null && link.getAddress() != null && !link.getAddress().isBlank()
                && !text.startsWith("http")) {
            return link.getAddress().trim();
        }
        return text;
    }

    /**
     * The code runner column, as the topic stores it: NONE or PYTHON, or null when the
     * cell holds something else. Blank means none. The template names the runner by its
     * value, so this file has to as well.
     */
    public static String runnerValue(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("none") || v.equals("no") || v.equals("-")) return "NONE";
        if (v.equals("python") || v.equals("py") || v.equals("yes")) return "PYTHON";
        return null;
    }

    /** The empty template, with the header the content team agreed on. */
    public static byte[] template() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Content");
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            Row row = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                Cell c = row.createCell(i);
                c.setCellValue(TEMPLATE_HEADERS.get(i));
                c.setCellStyle(head);
                sheet.setColumnWidth(i, i == 0 ? 8 * 256 : 26 * 256);
            }
            sheet.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        }
    }
}
