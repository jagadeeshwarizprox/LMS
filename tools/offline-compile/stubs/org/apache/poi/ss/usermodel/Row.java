package org.apache.poi.ss.usermodel;
public interface Row extends Iterable<Cell> {
    Cell getCell(int index);
    Cell createCell(int index);
    int getRowNum();
    short getLastCellNum();
    short getFirstCellNum();
    int getPhysicalNumberOfCells();
}
