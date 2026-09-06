package org.apache.poi.ss.usermodel;
import java.io.Closeable;
public interface Workbook extends Closeable, Iterable<Sheet> {
    int getNumberOfSheets();
    Sheet getSheetAt(int index);
    Sheet getSheet(String name);
    Sheet createSheet(String name);
    String getSheetName(int index);
    void write(java.io.OutputStream out) throws java.io.IOException;
}
