package org.apache.poi.ss.usermodel;
public interface Sheet extends Iterable<Row> {
    Row getRow(int index);
    Row createRow(int index);
    int getLastRowNum();
    int getFirstRowNum();
    int getPhysicalNumberOfRows();
    String getSheetName();
}
