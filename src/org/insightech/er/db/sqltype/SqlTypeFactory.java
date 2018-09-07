package org.insightech.er.db.sqltype;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.insightech.er.db.sqltype.SqlType.TypeKey;
import org.insightech.er.util.Check;
import org.insightech.er.util.POIUtils;

public class SqlTypeFactory {

    public static void load() throws IOException, ClassNotFoundException {
        final InputStream in = SqlTypeFactory.class.getResourceAsStream("/SqlType.xls");

        try {
            final HSSFWorkbook workBook = POIUtils.readExcelBook(in);

            final HSSFSheet sheet = workBook.getSheetAt(0);

            final Map<String, Map<SqlType, String>> dbSqlTypeToAliasMap = new HashMap<String, Map<SqlType, String>>();
            final Map<String, Map<String, SqlType>> dbAliasToSqlTypeMap = new HashMap<String, Map<String, SqlType>>();
            final Map<String, Map<TypeKey, SqlType>> dbSqlTypeMap = new HashMap<String, Map<TypeKey, SqlType>>();

            final HSSFRow headerRow = sheet.getRow(0);

            for (int colNum = 4; colNum < headerRow.getLastCellNum(); colNum += 6) {
                final String dbId = POIUtils.getCellValue(sheet, 0, colNum);

                dbSqlTypeToAliasMap.put(dbId, new LinkedHashMap<SqlType, String>());
                dbAliasToSqlTypeMap.put(dbId, new LinkedHashMap<String, SqlType>());
                dbSqlTypeMap.put(dbId, new LinkedHashMap<TypeKey, SqlType>());
            }

            SqlType.setDBAliasMap(dbSqlTypeToAliasMap, dbAliasToSqlTypeMap, dbSqlTypeMap);

            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                final HSSFRow row = sheet.getRow(rowNum);

                final String sqlTypeId = POIUtils.getCellValue(sheet, rowNum, 0);
                if (Check.isEmpty(sqlTypeId)) {
                    break;
                }
                final Class javaClass = Class.forName(POIUtils.getCellValue(sheet, rowNum, 1));
                final boolean needArgs = POIUtils.getBooleanCellValue(sheet, rowNum, 2);
                final boolean fullTextIndexable = POIUtils.getBooleanCellValue(sheet, rowNum, 3);

                final SqlType sqlType = new SqlType(sqlTypeId, javaClass, needArgs, fullTextIndexable);

                for (int colNum = 4; colNum < row.getLastCellNum(); colNum += 6) {

                    final String dbId = POIUtils.getCellValue(sheet, 0, colNum);

                    final Map<SqlType, String> sqlTypeToAliasMap = dbSqlTypeToAliasMap.get(dbId);
                    final Map<String, SqlType> aliasToSqlTypeMap = dbAliasToSqlTypeMap.get(dbId);

                    if (POIUtils.getCellColor(sheet, rowNum, colNum) != HSSFColor.GREY_50_PERCENT.index) {

                        final String alias = POIUtils.getCellValue(sheet, rowNum, colNum + 1);

                        if (!Check.isEmpty(alias)) {
                            aliasToSqlTypeMap.put(alias, sqlType);
                            sqlTypeToAliasMap.put(sqlType, alias);

                        } else {
                            final String aliasForConvert = POIUtils.getCellValue(sheet, rowNum, colNum + 2);

                            if (!Check.isEmpty(aliasForConvert)) {
                                sqlTypeToAliasMap.put(sqlType, aliasForConvert);
                            }
                        }
                    }

                    final String key = POIUtils.getCellValue(sheet, rowNum, colNum + 3);

                    if (!Check.isEmpty(key)) {
                        final int keySize = POIUtils.getIntCellValue(sheet, rowNum, colNum + 4);
                        final int keyDecimal = POIUtils.getIntCellValue(sheet, rowNum, colNum + 5);

                        final TypeKey typeKey = new TypeKey(key, keySize, keyDecimal);
                        sqlType.addToSqlTypeMap(typeKey, dbId);
                    }
                }
            }

        } finally {
            in.close();
        }

    }
}
