package org.apache.poi.ss.usermodel;
import java.util.Date;
public interface Cell {
    CellType getCellType();
    CellType getCachedFormulaResultType();
    String getStringCellValue();
    double getNumericCellValue();
    boolean getBooleanCellValue();
    String getCellFormula();
    Date getDateCellValue();
    java.time.LocalDateTime getLocalDateTimeCellValue();
    int getColumnIndex();
    int getRowIndex();
    Row getRow();
}
