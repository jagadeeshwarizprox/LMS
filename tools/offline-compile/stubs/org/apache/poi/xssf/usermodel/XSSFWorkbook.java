package org.apache.poi.xssf.usermodel;
import org.apache.poi.ss.usermodel.*;
public class XSSFWorkbook implements Workbook {
    public XSSFWorkbook() {}
    public int getNumberOfSheets() { return 0; }
    public Sheet getSheetAt(int index) { return null; }
    public Sheet getSheet(String name) { return null; }
    public Sheet createSheet(String name) { return null; }
    public String getSheetName(int index) { return null; }
    public void write(java.io.OutputStream out) throws java.io.IOException {}
    public Font createFont() { return null; }
    public CellStyle createCellStyle() { return null; }
    public CreationHelper getCreationHelper() { return null; }
    public java.util.Iterator<Sheet> iterator() { return null; }
    public void close() throws java.io.IOException {}
}
