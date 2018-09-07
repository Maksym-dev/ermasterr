package org.insightech.er.test;

import java.util.List;
import java.util.Map.Entry;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import org.insightech.er.db.DBManagerFactory;
import org.insightech.er.db.impl.mysql.MySQLDBManager;
import org.insightech.er.db.impl.postgres.PostgresDBManager;
import org.insightech.er.db.sqltype.SqlType;
import org.insightech.er.db.sqltype.SqlType.TypeKey;
import org.insightech.er.editor.model.diagram_contents.not_element.dictionary.TypeData;
import org.insightech.er.util.Format;

public class SqlTypeTest {
	
    private static Logger logger = Logger.getLogger(SqlType.class.getName());

    public static void main(final String[] args) {
    	String targetDb = PostgresDBManager.ID;
		logger.info("Total " + SqlType.getSqlTypeMapByDatabase(targetDb).size());
        for (final Entry<TypeKey, SqlType> entry : SqlType.getSqlTypeMapByDatabase(targetDb).entrySet()) {
            logger.info(entry.getKey().toString() + ":" + entry.getValue().getAlias(targetDb));
        }
        
        main2(args);
    }
    
    public static void main2(final String[] args) {
        String targetDb = PostgresDBManager.ID;

        final boolean zerofill = false;
        final int testIntValue = 5;

        final int maxIdLength = 37;

        final StringBuilder msg = new StringBuilder();

        msg.append("\n");

        final List<SqlType> list = SqlType.getAllSqlType();

        final List<String> dbList = DBManagerFactory.getAllDBList();
        int errorCount = 0;

        String str = "ID";

        if (targetDb == null) {
            msg.append(str);

            for (final String db : dbList) {
                int spaceLength = maxIdLength - str.length();
                if (spaceLength < 4) {
                    spaceLength = 4;
                }

                for (int i = 0; i < spaceLength; i++) {
                    msg.append(" ");
                }

                str = db;
                msg.append(db);
            }

            msg.append("\n");
            msg.append("\n");

            final StringBuilder builder = new StringBuilder();

            for (final SqlType type : list) {
                builder.append(type.getName());
                int spaceLength = maxIdLength - type.getName().length();
                if (spaceLength < 4) {
                    spaceLength = 4;
                }

                for (final String db : dbList) {
                    for (int i = 0; i < spaceLength; i++) {
                        builder.append(" ");
                    }

                    final String alias = type.getAlias(db);

                    if (alias != null) {
                        builder.append(type.getAlias(db));
                        spaceLength = maxIdLength - type.getAlias(db).length();
                        if (spaceLength < 4) {
                            spaceLength = 4;
                        }

                    } else {
                        if (type.isUnsupported(db)) {
                            builder.append("□□□□□□");
                        } else {
                            builder.append("■■■■■■");
                            errorCount++;
                        }

                        spaceLength = maxIdLength - "□□□□□□".length();
                        if (spaceLength < 4) {
                            spaceLength = 4;
                        }
                    }
                }

                builder.append("\r\n");
            }

            final String allColumn = builder.toString();
            msg.append(allColumn + "\n");
        }

        int errorCount2 = 0;
        int errorCount3 = 0;

        for (final String db : dbList) {
            if (targetDb == null || db.equals(targetDb)) {
                if (targetDb == null) {
                    msg.append("-- for " + db + "\n");
                }
                msg.append("CREATE TABLE TYPE_TEST (\n");

                int count = 0;

                for (final SqlType type : list) {
                    final String alias = type.getAlias(db);
                    if (alias == null) {
                        continue;
                    }

                    if (count != 0) {
                        msg.append(",\n");
                    }
                    msg.append("\tCOL_" + count + " ");

                    if (type.isNeedLength(db) && type.isNeedDecimal(db)) {
                        final TypeData typeData = new TypeData(Integer.valueOf(testIntValue), Integer.valueOf(testIntValue), false, null, false, false, false, null, false);

                        str = Format.formatType(type, typeData, db, true);

                        if (zerofill && db.equals(MySQLDBManager.ID)) {
                            if (type.isNumber()) {
                                str = str + " unsigned zerofill";
                            }
                        }

                        if (str.equals(alias)) {
                            errorCount3++;
                            msg.append("×3");
                        }

                    } else if (type.isNeedLength(db)) {
                        final TypeData typeData = new TypeData(Integer.valueOf(testIntValue), null, false, null, false, false, false, null, false);

                        str = Format.formatType(type, typeData, db, true);

                        if (zerofill && db.equals(MySQLDBManager.ID)) {
                            if (type.isNumber()) {
                                str = str + " unsigned zerofill";
                            }
                        }

                        if (str.equals(alias)) {
                            errorCount3++;
                            msg.append("×3");
                        }

                    } else if (type.isNeedDecimal(db)) {
                        final TypeData typeData = new TypeData(null, Integer.valueOf(testIntValue), false, null, false, false, false, null, false);

                        str = Format.formatType(type, typeData, db, true);

                        if (zerofill && db.equals(MySQLDBManager.ID)) {
                            if (type.isNumber()) {
                                str = str + " unsigned zerofill";
                            }
                        }

                        if (str.equals(alias)) {
                            errorCount3++;
                            msg.append("×3");
                        }

                    } else if (type.doesNeedArgs()) {
                        str = alias + "('1')";

                    } else {
                        str = alias;

                        if (zerofill && db.equals(MySQLDBManager.ID)) {
                            if (type.isNumber()) {
                                str = str + " unsigned zerofill";
                            }
                        }

                        if (str.equals("uniqueidentifier rowguidcol")) {
                            str += " not null unique";
                        }
                    }

                    if (str != null) {

                        final Matcher m1 = SqlType.NEED_LENGTH_PATTERN.matcher(str);
                        final Matcher m2 = SqlType.NEED_DECIMAL_PATTERN1.matcher(str);
                        final Matcher m3 = SqlType.NEED_DECIMAL_PATTERN2.matcher(str);

                        if (m1.matches() || m2.matches() || m3.matches()) {
                            errorCount2++;
                            msg.append("×2");
                        }
                    }

                    msg.append(str);

                    count++;
                }
                msg.append("\n");
                msg.append(");\n");
                msg.append("\n");
            }
        }

        msg.append("\n");

        if (targetDb == null) {
            msg.append(errorCount + " 個の型が変換できませんでした。\n");
            msg.append(errorCount2 + " 個の数字型の指定が不足しています。\n");
            msg.append(errorCount3 + " 個の数字型の指定が余分です。\n");
        }

        System.out.println(msg.toString());
    }
}
