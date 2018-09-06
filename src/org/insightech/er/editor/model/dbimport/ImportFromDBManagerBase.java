package org.insightech.er.editor.model.dbimport;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.insightech.er.ERDiagramActivator;
import org.insightech.er.ResourceString;
import org.insightech.er.common.exception.InputException;
import org.insightech.er.db.sqltype.SqlType;
import org.insightech.er.editor.TranslationResources;
import org.insightech.er.editor.model.ERDiagram;
import org.insightech.er.editor.model.diagram_contents.element.connection.Relation;
import org.insightech.er.editor.model.diagram_contents.element.node.table.ERTable;
import org.insightech.er.editor.model.diagram_contents.element.node.table.column.Column;
import org.insightech.er.editor.model.diagram_contents.element.node.table.column.NormalColumn;
import org.insightech.er.editor.model.diagram_contents.element.node.table.index.Index;
import org.insightech.er.editor.model.diagram_contents.element.node.table.properties.TableViewProperties;
import org.insightech.er.editor.model.diagram_contents.element.node.table.unique_key.ComplexUniqueKey;
import org.insightech.er.editor.model.diagram_contents.element.node.view.View;
import org.insightech.er.editor.model.diagram_contents.not_element.dictionary.TypeData;
import org.insightech.er.editor.model.diagram_contents.not_element.dictionary.UniqueWordDictionary;
import org.insightech.er.editor.model.diagram_contents.not_element.dictionary.Word;
import org.insightech.er.editor.model.diagram_contents.not_element.sequence.Sequence;
import org.insightech.er.editor.model.diagram_contents.not_element.tablespace.Tablespace;
import org.insightech.er.editor.model.diagram_contents.not_element.trigger.Trigger;
import org.insightech.er.editor.model.progress_monitor.ProgressMonitor;
import org.insightech.er.editor.model.settings.DBSetting;
import org.insightech.er.util.Check;
import org.insightech.er.util.Format;

public abstract class ImportFromDBManagerBase implements ImportFromDBManager {

    private static Logger logger = Logger.getLogger(ImportFromDBManagerBase.class.getName());

    private static final boolean LOG_SQL_TYPE = false;

    private static final Pattern AS_PATTERN = Pattern.compile("(.+) [aA][sS] (.+)");

    private static final Pattern TYPE_WITH_LENGTH_PATTERN = Pattern.compile("(.+)\\((\\d+)\\).*");

    private static final Pattern TYPE_WITH_DECIMAL_PATTERN = Pattern.compile("(.+)\\((\\d+),(\\d+)\\).*");

    protected Connection con;

    private DatabaseMetaData metaData;

    protected DBSetting dbSetting;

    private ERDiagram diagram;

    private List<DBObject> dbObjectList;

    private final Map<String, ERTable> tableMap;

    protected Map<String, String> tableCommentMap;

    protected Map<String, Map<String, ColumnData>> columnDataCache;

    protected Map<String, List<ForeignKeyData>> tableForeignKeyDataMap;

    private final UniqueWordDictionary dictionary;

    private List<ERTable> importedTables;

    private List<Sequence> importedSequences;

    private List<Trigger> importedTriggers;

    private List<Tablespace> importedTablespaces;

    private List<View> importedViews;

    private Exception exception;

    protected TranslationResources translationResources;

    private boolean useCommentAsLogicalName;

    private boolean mergeWord;

    private boolean mergeTables;

    private int taskCount;

    private int taskTotalCount;

    protected static class ColumnData {
        public String columnName;

        public String type;

        public int size;

        public int decimalDegits;

        public int nullable;

        public String defaultValue;

        public String description;

        public String constraint;

        public String enumData;

        public String characterSet;

        public String collation;

        public boolean isBinary;

        public boolean charSemantics;

        @Override
        public String toString() {
            return "ColumnData [columnName=" + columnName + ", type=" + type + ", size=" + size + ", decimalDegits=" + decimalDegits + "]";
        }

    }

    private static class ForeignKeyData {
        private String name;

        private String sourceTableName;

        private String sourceSchemaName;

        private String sourceColumnName;

        private String targetTableName;

        private String targetSchemaName;

        private String targetColumnName;

        private short updateRule;

        private short deleteRule;
    }

    protected static class PrimaryKeyData {
        private String columnName;

        private String constraintName;
    }

    public ImportFromDBManagerBase() {
        tableMap = new HashMap<String, ERTable>();
        tableCommentMap = new HashMap<String, String>();
        columnDataCache = new HashMap<String, Map<String, ColumnData>>();
        tableForeignKeyDataMap = new HashMap<String, List<ForeignKeyData>>();
        dictionary = new UniqueWordDictionary();
    }

    @Override
    public void init(final Connection con, final DBSetting dbSetting, final ERDiagram diagram, final List<DBObject> dbObjectList, final boolean useCommentAsLogicalName, final boolean mergeWord, final boolean mergeTables) throws SQLException {
        this.con = con;
        this.dbSetting = dbSetting;
        this.diagram = diagram;
        this.dbObjectList = dbObjectList;
        this.useCommentAsLogicalName = useCommentAsLogicalName;
        this.mergeWord = mergeWord;
        this.mergeTables = mergeTables;

        metaData = con.getMetaData();
        translationResources = new TranslationResources(diagram.getDiagramContents().getSettings().getTranslationSetting());

        if (this.mergeWord) {
            dictionary.init(this.diagram);
        }
    }

    public void run(final ProgressMonitor monitor) throws InvocationTargetException, InterruptedException {

        try {
            taskTotalCount = dbObjectList.size();

            monitor.beginTask(ResourceString.getResourceString("dialog.message.import.db.objects"), taskTotalCount);

            cacheTableComment(monitor);
            cacheColumnData(dbObjectList, monitor);

            importedSequences = importSequences(dbObjectList, monitor);
            importedTriggers = importTriggers(dbObjectList, monitor);
            importedTablespaces = importTablespaces(dbObjectList, monitor);
            importedTables = importTables(dbObjectList, monitor);
            importedTables.addAll(importSynonyms());

            this.setForeignKeys(importedTables);

            importedViews = importViews(dbObjectList, monitor);

        } catch (final InterruptedException e) {
            throw e;

        } catch (final Exception e) {
            logger.log(Level.WARNING, e.getMessage(), e);
            exception = e;

        }

        monitor.done();
    }

    protected void cacheColumnData(final List<DBObject> dbObjectList, final ProgressMonitor monitor) throws SQLException, InterruptedException {
        final Set<String> schemas = new HashSet<String>();

        for (final DBObject dbObject : dbObjectList) {
            if (!dbObject.getType().equals(DBObject.TYPE_TABLE)) {
                continue;
            }

            final String schemaName = dbObject.getSchema();

            if (!schemas.contains(schemaName)) {
                if (monitor != null) {
                    String displayName = schemaName;

                    if (schemaName == null) {
                        displayName = ResourceString.getResourceString("label.none");
                    }
                    monitor.subTask("reading schema: " + displayName);
                }

                cacheColumnDataX(schemaName, null, dbObjectList, monitor);

                schemas.add(schemaName);

                if (monitor != null && monitor.isCanceled()) {
                    throw new InterruptedException("Cancel has been requested.");
                }
            }
        }
    }

    protected void cacheColumnDataX(final String schemaName, String tableName, final List<DBObject> dbObjectList, final ProgressMonitor monitor) throws SQLException, InterruptedException {
        ResultSet columnSet = null;

        try {
            columnSet = metaData.getColumns(null, schemaName, tableName, null);

            while (columnSet.next()) {
                tableName = columnSet.getString("TABLE_NAME");
                final String schema = columnSet.getString("TABLE_SCHEM");

                final String tableNameWithSchema = dbSetting.getTableNameWithSchema(tableName, schema);

                Map<String, ColumnData> cache = columnDataCache.get(tableNameWithSchema);
                if (cache == null) {
                    cache = new LinkedHashMap<String, ColumnData>();
                    columnDataCache.put(tableNameWithSchema, cache);
                }

                final ColumnData columnData = createColumnData(columnSet);

                cacheOtherColumnData(tableName, schema, columnData);

                cache.put(columnData.columnName, columnData);
            }

        } finally {
            if (columnSet != null) {
                columnSet.close();
            }
        }
    }

    protected ColumnData createColumnData(final ResultSet columnSet) throws SQLException {
        final ColumnData columnData = new ColumnData();
        columnData.columnName = columnSet.getString("COLUMN_NAME");
        columnData.type = columnSet.getString("TYPE_NAME").toLowerCase();
        columnData.size = columnSet.getInt("COLUMN_SIZE");
        columnData.decimalDegits = columnSet.getInt("DECIMAL_DIGITS");
        columnData.nullable = columnSet.getInt("NULLABLE");
        columnData.defaultValue = columnSet.getString("COLUMN_DEF");
        columnData.charSemantics = columnSet.getInt("CHAR_OCTET_LENGTH") == columnData.size;

        if (columnData.defaultValue != null) {
            if ("bit".equals(columnData.type)) {
                final byte[] bits = columnData.defaultValue.getBytes();

                columnData.defaultValue = "";

                for (int i = 0; i < bits.length; i++) {
                    columnData.defaultValue += bits[i];
                }
            }
        }

        columnData.description = columnSet.getString("REMARKS");

        return columnData;
    }

    protected void cacheOtherColumnData(final String tableName, final String schema, final ColumnData columnData) throws SQLException {}

    protected void cacheTableComment(final ProgressMonitor monitor) throws SQLException, InterruptedException {}

    private List<Sequence> importSequences(final List<DBObject> dbObjectList, final ProgressMonitor monitor) throws SQLException, InterruptedException {
        final List<Sequence> list = new ArrayList<Sequence>();

        for (final Iterator<DBObject> iter = dbObjectList.iterator(); iter.hasNext();) {
            final DBObject dbObject = iter.next();

            if (DBObject.TYPE_SEQUENCE.equals(dbObject.getType())) {
                iter.remove();
                taskCount++;

                final String name = dbObject.getName();
                final String schema = dbObject.getSchema();
                final String nameWithSchema = dbSetting.getTableNameWithSchema(name, schema);

                monitor.subTask("(" + taskCount + "/" + taskTotalCount + ") [" + dbObject.getType().toUpperCase() + "] " + nameWithSchema);
                monitor.worked(1);

                final Sequence sequence = importSequence(schema, name);

                if (sequence != null) {
                    list.add(sequence);
                }
            }

            if (monitor.isCanceled()) {
                throw new InterruptedException("Cancel has been requested.");
            }
        }

        return list;
    }

    protected Sequence importSequence(final String schema, final String sequenceName) throws SQLException {
        PreparedStatement stmt = null;
        ResultSet rs = null;

        final String sequenceNameWithSchema = getTableNameWithSchema(schema, sequenceName);

        try {
            stmt = con.prepareStatement("SELECT * FROM " + sequenceNameWithSchema);
            rs = stmt.executeQuery();

            if (rs.next()) {
                final Sequence sequence = new Sequence();

                sequence.setName(sequenceName);
                sequence.setSchema(schema);
                sequence.setIncrement(rs.getInt("INCREMENT_BY"));
                sequence.setMinValue(rs.getLong("MIN_VALUE"));

                final BigDecimal maxValue = rs.getBigDecimal("MAX_VALUE");

                sequence.setMaxValue(maxValue);
                sequence.setStart(rs.getLong("LAST_VALUE"));
                sequence.setCache(rs.getInt("CACHE_VALUE"));
                sequence.setCycle(rs.getBoolean("IS_CYCLED"));

                return sequence;
            }

            return null;

        } finally {
            this.close(rs);
            this.close(stmt);
        }
    }

    private List<Trigger> importTriggers(final List<DBObject> dbObjectList, final ProgressMonitor monitor) throws SQLException, InterruptedException {
        final List<Trigger> list = new ArrayList<Trigger>();

        for (final Iterator<DBObject> iter = dbObjectList.iterator(); iter.hasNext();) {
            final DBObject dbObject = iter.next();

            if (DBObject.TYPE_TRIGGER.equals(dbObject.getType())) {
                iter.remove();
                taskCount++;

                final String name = dbObject.getName();
                final String schema = dbObject.getSchema();
                final String nameWithSchema = dbSetting.getTableNameWithSchema(name, schema);

                monitor.subTask("(" + taskCount + "/" + taskTotalCount + ") [" + dbObject.getType().toUpperCase() + "] " + nameWithSchema);
                monitor.worked(1);

                final Trigger trigger = importTrigger(schema, name);

                if (trigger != null) {
                    list.add(trigger);
                }
            }

            if (monitor.isCanceled()) {
                throw new InterruptedException("Cancel has been requested.");
            }
        }

        return list;
    }

    protected Trigger importTrigger(final String schema, final String triggerName) throws SQLException {
        //
        return null;
    }

    protected List<ERTable> importTables(final List<DBObject> dbObjectList, final ProgressMonitor monitor) throws SQLException, InterruptedException {
        final List<ERTable> list = new ArrayList<ERTable>();

        for (final Iterator<DBObject> iter = dbObjectList.iterator(); iter.hasNext();) {
            final DBObject dbObject = iter.next();

            if (DBObject.TYPE_TABLE.equals(dbObject.getType())) {
                iter.remove();
                taskCount++;

                final String tableName = dbObject.getName();
                final String schema = dbObject.getSchema();
                final String tableNameWithSchema = dbSetting.getTableNameWithSchema(tableName, schema);

                monitor.subTask("(" + taskCount + "/" + taskTotalCount + ") [" + dbObject.getType().toUpperCase() + "] " + tableNameWithSchema);
                monitor.worked(1);

                final ERTable table = importTable(tableNameWithSchema, tableName, schema);

                if (table != null) {
                    list.add(table);
                }
            }

            if (monitor.isCanceled()) {
                throw new InterruptedException("Cancel has been requested.");
            }
        }

        return list;
    }

    protected List<ERTable> importSynonyms() throws SQLException, InterruptedException {
        return new ArrayList<ERTable>();
    }

    protected String getConstraintName(final PrimaryKeyData data) {
        return data.constraintName;
    }

    protected ERTable importTable(final String tableNameWithSchema, final String tableName, final String schema) throws SQLException, InterruptedException {
        String autoIncrementColumnName = null;
        try {
            autoIncrementColumnName = getAutoIncrementColumnName(con, getTableNameWithSchema(schema, tableName));
        } catch (final Exception e) {
            logger.log(Level.WARNING, e.getMessage());
            return null;
        }

        final ERTable table = new ERTable();
        final TableViewProperties tableProperties = table.getTableViewProperties(dbSetting.getDbsystem());
        tableProperties.setSchema(schema);

        table.setPhysicalName(tableName);
        table.setLogicalName(translationResources.translate(tableName));

        table.setDescription(tableCommentMap.get(tableNameWithSchema));

        final List<PrimaryKeyData> primaryKeys = getPrimaryKeys(table, metaData);
        if (!primaryKeys.isEmpty()) {
            table.setPrimaryKeyName(getConstraintName(primaryKeys.get(0)));
        }

        final List<Index> indexes = getIndexes(table, metaData, primaryKeys);

        final List<Column> columns = getColumns(tableNameWithSchema, tableName, schema, indexes, primaryKeys, autoIncrementColumnName);

        table.setColumns(columns);
        table.setIndexes(indexes);

        tableMap.put(tableNameWithSchema, table);

        for (final Index index : indexes) {
            setIndexColumn(table, index);
        }

        setTableViewProperties(tableName, tableProperties);

        return table;
    }

    protected void setTableViewProperties(final String tableName, final TableViewProperties tableViewProperties) {}

    protected String getTableNameWithSchema(final String schema, final String tableName) {
        return dbSetting.getTableNameWithSchema(tableName, schema);
    }

    protected void setForeignKeys(final List<ERTable> list) throws SQLException {
        cacheForeignKeyData();

        for (final ERTable target : list) {
            if (tableForeignKeyDataMap != null) {
                setForeignKeysUsingCache(target);
            } else {
                this.setForeignKeys(target);
            }
        }
    }

    private String getAutoIncrementColumnName(final Connection con, final String tableNameWithSchema) throws SQLException {
        String autoIncrementColumnName = null;

        Statement stmt = null;
        ResultSet rs = null;

        try {
            stmt = con.createStatement();

            rs = stmt.executeQuery("SELECT * FROM " + tableNameWithSchema);
            final ResultSetMetaData md = rs.getMetaData();

            for (int i = 0; i < md.getColumnCount(); i++) {
                if (md.isAutoIncrement(i + 1)) {
                    autoIncrementColumnName = md.getColumnName(i + 1);
                    break;
                }
            }

        } finally {
            this.close(rs);
            this.close(stmt);
        }

        return autoIncrementColumnName;
    }

    protected List<Index> getIndexes(final ERTable table, final DatabaseMetaData metaData, final List<PrimaryKeyData> primaryKeys) throws SQLException {

        final List<Index> indexes = new ArrayList<Index>();

        final Map<String, Index> indexMap = new HashMap<String, Index>();

        ResultSet indexSet = null;

        try {
            indexSet = metaData.getIndexInfo(null, table.getTableViewProperties(dbSetting.getDbsystem()).getSchema(), table.getPhysicalName(), false, true);

            while (indexSet.next()) {
                String name = null;
                try {
                    name = indexSet.getString("INDEX_NAME");

                } catch (final SQLException e) {
                    logger.log(Level.WARNING, "Cannot get Index Info of [" + table.getTableViewProperties(dbSetting.getDbsystem()).getSchema() + ":" + table.getPhysicalName() + "]");
                    continue;
                }

                if (name == null) {
                    continue;
                }

                Index index = indexMap.get(name);

                if (index == null) {
                    final boolean nonUnique = indexSet.getBoolean("NON_UNIQUE");
                    String type = null;
                    final short indexType = indexSet.getShort("TYPE");
                    if (indexType == DatabaseMetaData.tableIndexOther) {
                        type = "BTREE";
                    }

                    // DatabaseMetaData.tableIndexClustered
                    // DatabaseMetaData.tableIndexOther
                    // DatabaseMetaData.tableIndexStatistic

                    index = new Index(table, name, nonUnique, type, null);

                    indexMap.put(name, index);
                    indexes.add(index);
                }

                String columnName = indexSet.getString("COLUMN_NAME");
                final String ascDesc = indexSet.getString("ASC_OR_DESC");

                if (columnName.startsWith("\"") && columnName.endsWith("\"")) {
                    columnName = columnName.substring(1, columnName.length() - 1);
                }

                Boolean desc = null;

                if ("A".equals(ascDesc)) {
                    desc = Boolean.FALSE;
                } else if ("D".equals(ascDesc)) {
                    desc = Boolean.TRUE;
                }

                index.addColumnName(columnName, desc);
            }

        } catch (final SQLException e) {
            throw e;
        } finally {
            this.close(indexSet);
        }

        for (final Iterator<Index> iter = indexes.iterator(); iter.hasNext();) {
            final Index index = iter.next();
            final List<String> indexColumns = index.getColumnNames();

            if (indexColumns.size() == primaryKeys.size()) {
                boolean equals = true;

                for (int i = 0; i < indexColumns.size(); i++) {
                    if (!indexColumns.get(i).equals(primaryKeys.get(i).columnName)) {
                        equals = false;
                        break;
                    }
                }

                if (equals) {
                    iter.remove();
                }
            }
        }

        return indexes;
    }

    private void setIndexColumn(final ERTable erTable, final Index index) {
        for (final String columnName : index.getColumnNames()) {
            for (final Column column : erTable.getColumns()) {
                if (column instanceof NormalColumn) {
                    final NormalColumn normalColumn = (NormalColumn) column;

                    if (normalColumn.getPhysicalName().equals(columnName)) {
                        index.addColumn(normalColumn);
                        break;
                    }
                }
            }
        }
    }

    private List<PrimaryKeyData> getPrimaryKeys(final ERTable table, final DatabaseMetaData metaData) throws SQLException {
        final List<PrimaryKeyData> primaryKeys = new ArrayList<PrimaryKeyData>();

        ResultSet primaryKeySet = null;

        try {
            primaryKeySet = metaData.getPrimaryKeys(null, table.getTableViewProperties(dbSetting.getDbsystem()).getSchema(), table.getPhysicalName());
            while (primaryKeySet.next()) {
                final PrimaryKeyData data = new PrimaryKeyData();

                data.columnName = primaryKeySet.getString("COLUMN_NAME");
                data.constraintName = primaryKeySet.getString("PK_NAME");

                primaryKeys.add(data);
            }

        } catch (final SQLException e) {
            // Microsoft Access does not support getPrimaryKeys

        } finally {
            this.close(primaryKeySet);
        }

        return primaryKeys;
    }

    protected Map<String, ColumnData> getColumnDataMap(final String tableNameWithSchema, final String tableName, final String schema) throws SQLException, InterruptedException {
        return columnDataCache.get(tableNameWithSchema);
    }

    private List<Column> getColumns(final String tableNameWithSchema, final String tableName, final String schema, final List<Index> indexes, final List<PrimaryKeyData> primaryKeys, final String autoIncrementColumnName) throws SQLException, InterruptedException {
        final List<Column> columns = new ArrayList<Column>();

        final Map<String, ColumnData> columnDataMap = getColumnDataMap(tableNameWithSchema, tableName, schema);
        if (columnDataMap == null) {
            return new ArrayList<Column>();
        }

        final Collection<ColumnData> columnSet = columnDataMap.values();

        for (final ColumnData columnData : columnSet) {
            final String columnName = columnData.columnName;
            String type = columnData.type;

            boolean array = false;
            Integer arrayDimension = null;
            boolean unsigned = false;

            boolean zerofill = false;

            final int zerofillIndex = type.toUpperCase().indexOf(" ZEROFILL");
            if (zerofillIndex != -1) {
                zerofill = true;
                type = type.substring(0, zerofillIndex);
            }

            final int unsignedIndex = type.toUpperCase().indexOf(" UNSIGNED");
            if (unsignedIndex != -1) {
                unsigned = true;
                type = type.substring(0, unsignedIndex);
            }

            final int arrayStartIndex = type.indexOf("[");
            if (arrayStartIndex != -1) {
                array = true;
                final String str = type.substring(arrayStartIndex + 1, type.indexOf("]"));
                arrayDimension = Integer.parseInt(str);
                type = type.substring(0, arrayStartIndex);
            }

            int size = 0;
            int decimalDegits = 0;

            if (zerofillIndex != -1) {
                Matcher matcher = TYPE_WITH_DECIMAL_PATTERN.matcher(type);
                if (matcher.find()) {
                    type = matcher.group(1);
                    size = Integer.parseInt(matcher.group(2));
                    decimalDegits = Integer.parseInt(matcher.group(3));

                } else {
                    matcher = TYPE_WITH_LENGTH_PATTERN.matcher(type);

                    if (matcher.find()) {
                        type = matcher.group(1);
                        size = Integer.parseInt(matcher.group(2));
                    }
                }

            } else {
                size = columnData.size;
                decimalDegits = columnData.decimalDegits;
            }

            final Integer length = new Integer(size);
            final Integer decimal = new Integer(decimalDegits);

            final SqlType sqlType = SqlType.valueOf(dbSetting.getDbsystem(), type, size, decimal);

            if (sqlType == null || LOG_SQL_TYPE) {
                logger.info(columnName + ": " + type + ", " + size + ", " + columnData.decimalDegits);
            }

            boolean notNull = false;
            if (columnData.nullable == DatabaseMetaData.columnNoNulls) {
                notNull = true;
            }

            String defaultValue = Format.null2blank(columnData.defaultValue);
            if (sqlType != null) {
                if (SqlType.SQL_TYPE_ID_SERIAL.equals(sqlType.getId()) || SqlType.SQL_TYPE_ID_BIG_SERIAL.equals(sqlType.getId())) {
                    defaultValue = "";
                }
            }

            final String description = Format.null2blank(columnData.description);
            final String constraint = Format.null2blank(columnData.constraint);

            boolean primaryKey = false;

            for (final PrimaryKeyData primaryKeyData : primaryKeys) {
                if (columnName.equals(primaryKeyData.columnName)) {
                    primaryKey = true;
                    break;
                }
            }

            final boolean uniqueKey = isUniqueKey(columnName, indexes, primaryKeys);

            final boolean autoIncrement = columnName.equalsIgnoreCase(autoIncrementColumnName);

            String logicalName = null;
            if (useCommentAsLogicalName && !Check.isEmpty(description)) {
                logicalName = description.replaceAll("[\r\n]", "");
            }
            if (Check.isEmpty(logicalName)) {
                logicalName = translationResources.translate(columnName);
            }

            final String args = columnData.enumData;

            final TypeData typeData = new TypeData(length, decimal, array, arrayDimension, unsigned, zerofill, columnData.isBinary, args, columnData.charSemantics);

            Word word = new Word(columnName, logicalName, sqlType, typeData, description, diagram.getDatabase());
            word = dictionary.getUniqueWord(word);

            // TODO UNIQUE KEY

            final NormalColumn column = new NormalColumn(word, notNull, primaryKey, uniqueKey, autoIncrement, defaultValue, constraint, null, columnData.characterSet, columnData.collation);

            columns.add(column);
        }

        return columns;
    }

    private boolean isUniqueKey(final String columnName, final List<Index> indexes, final List<PrimaryKeyData> primaryKeys) {
        String primaryKey = null;

        if (primaryKeys.size() == 1) {
            primaryKey = primaryKeys.get(0).columnName;
        }

        if (columnName == null) {
            return false;
        }

        for (final Index index : indexes) {
            final List<String> columnNames = index.getColumnNames();
            if (columnNames.size() == 1) {
                final String indexColumnName = columnNames.get(0);
                if (columnName.equals(indexColumnName)) {
                    if (!index.isNonUnique()) {
                        if (!columnName.equals(primaryKey)) {
                            indexes.remove(index);
                            return true;
                        }
                        return false;
                    }
                }
            }
        }

        return false;
    }

    private boolean isCyclicForeignKye(final ForeignKeyData foreignKeyData) {
        if (foreignKeyData.sourceSchemaName == null) {
            if (foreignKeyData.targetSchemaName != null) {
                return false;
            }

        } else if (!foreignKeyData.sourceSchemaName.equals(foreignKeyData.targetSchemaName)) {
            return false;
        }

        if (!foreignKeyData.sourceTableName.equals(foreignKeyData.targetTableName)) {
            return false;
        }

        if (!foreignKeyData.sourceColumnName.equals(foreignKeyData.targetColumnName)) {
            return false;
        }

        return true;
    }

    protected void cacheForeignKeyData() throws SQLException {
        ResultSet foreignKeySet = null;
        try {
            foreignKeySet = metaData.getImportedKeys(null, null, null);

            while (foreignKeySet.next()) {
                final ForeignKeyData foreignKeyData = new ForeignKeyData();

                foreignKeyData.name = foreignKeySet.getString("FK_NAME");
                foreignKeyData.sourceSchemaName = foreignKeySet.getString("PKTABLE_SCHEM");
                foreignKeyData.sourceTableName = foreignKeySet.getString("PKTABLE_NAME");
                foreignKeyData.sourceColumnName = foreignKeySet.getString("PKCOLUMN_NAME");
                foreignKeyData.targetSchemaName = foreignKeySet.getString("FKTABLE_SCHEM");
                foreignKeyData.targetTableName = foreignKeySet.getString("FKTABLE_NAME");
                foreignKeyData.targetColumnName = foreignKeySet.getString("FKCOLUMN_NAME");
                foreignKeyData.updateRule = foreignKeySet.getShort("UPDATE_RULE");
                foreignKeyData.deleteRule = foreignKeySet.getShort("DELETE_RULE");

                if (isCyclicForeignKye(foreignKeyData)) {
                    continue;
                }

                final String key = dbSetting.getTableNameWithSchema(foreignKeyData.targetTableName, foreignKeyData.targetSchemaName);

                List<ForeignKeyData> foreignKeyDataList = tableForeignKeyDataMap.get(key);

                if (foreignKeyDataList == null) {
                    foreignKeyDataList = new ArrayList<ForeignKeyData>();
                    tableForeignKeyDataMap.put(key, foreignKeyDataList);
                }

                foreignKeyDataList.add(foreignKeyData);
            }
        } catch (final SQLException e) {
            tableForeignKeyDataMap = null;

        } finally {
            this.close(foreignKeySet);
        }
    }

    private void setForeignKeysUsingCache(final ERTable target) throws SQLException {
        String tableName = target.getPhysicalName();
        final String schema = target.getTableViewProperties(dbSetting.getDbsystem()).getSchema();

        tableName = dbSetting.getTableNameWithSchema(tableName, schema);

        final List<ForeignKeyData> foreignKeyList = tableForeignKeyDataMap.get(tableName);

        if (foreignKeyList == null) {
            return;
        }

        final Map<String, List<ForeignKeyData>> sameNameForeignKeyDataMap = collectSameNameForeignKeyData(foreignKeyList);

        for (final Map.Entry<String, List<ForeignKeyData>> entry : sameNameForeignKeyDataMap.entrySet()) {
            createRelation(target, entry.getValue());
        }
    }

    private void setForeignKeys(final ERTable target) throws SQLException {
        final String tableName = target.getPhysicalName();
        final String schemaName = target.getTableViewProperties(dbSetting.getDbsystem()).getSchema();

        ResultSet foreignKeySet = null;

        try {
            foreignKeySet = metaData.getImportedKeys(null, schemaName, tableName);

            final List<ForeignKeyData> foreignKeyList = new ArrayList<ForeignKeyData>();

            while (foreignKeySet.next()) {
                final ForeignKeyData foreignKeyData = new ForeignKeyData();

                foreignKeyData.name = foreignKeySet.getString("FK_NAME");
                foreignKeyData.sourceTableName = foreignKeySet.getString("PKTABLE_NAME");
                foreignKeyData.sourceSchemaName = foreignKeySet.getString("PKTABLE_SCHEM");
                foreignKeyData.sourceColumnName = foreignKeySet.getString("PKCOLUMN_NAME");
                foreignKeyData.targetSchemaName = foreignKeySet.getString("FKTABLE_SCHEM");
                foreignKeyData.targetColumnName = foreignKeySet.getString("FKCOLUMN_NAME");
                foreignKeyData.updateRule = foreignKeySet.getShort("UPDATE_RULE");
                foreignKeyData.deleteRule = foreignKeySet.getShort("DELETE_RULE");

                foreignKeyList.add(foreignKeyData);
            }

            if (foreignKeyList.isEmpty()) {
                return;
            }

            final Map<String, List<ForeignKeyData>> sameNameForeignKeyDataMap = collectSameNameForeignKeyData(foreignKeyList);

            for (final Map.Entry<String, List<ForeignKeyData>> entry : sameNameForeignKeyDataMap.entrySet()) {
                createRelation(target, entry.getValue());
            }

        } catch (final SQLException e) {
            // microsoft access does not support getImportedKeys

        } finally {
            this.close(foreignKeySet);
        }
    }

    private Map<String, List<ForeignKeyData>> collectSameNameForeignKeyData(final List<ForeignKeyData> foreignKeyList) {
        final Map<String, List<ForeignKeyData>> map = new HashMap<String, List<ForeignKeyData>>();

        for (final ForeignKeyData foreignKyeData : foreignKeyList) {
            List<ForeignKeyData> list = map.get(foreignKyeData.name);
            if (list == null) {
                list = new ArrayList<ForeignKeyData>();
                map.put(foreignKyeData.name, list);
            }

            list.add(foreignKyeData);
        }

        return map;
    }

    private Relation createRelation(final ERTable target, final List<ForeignKeyData> foreignKeyDataList) {
        final ForeignKeyData representativeData = foreignKeyDataList.get(0);

        String sourceTableName = representativeData.sourceTableName;
        final String sourceSchemaName = representativeData.sourceSchemaName;

        sourceTableName = dbSetting.getTableNameWithSchema(sourceTableName, sourceSchemaName);

        final ERTable source = tableMap.get(sourceTableName);
        if (source == null) {
            return null;
        }

        boolean referenceForPK = true;

        final List<NormalColumn> primaryKeys = source.getPrimaryKeys();
        if (primaryKeys.size() != foreignKeyDataList.size()) {
            referenceForPK = false;
        }

        final Map<NormalColumn, NormalColumn> referenceMap = new HashMap<NormalColumn, NormalColumn>();

        for (final ForeignKeyData foreignKeyData : foreignKeyDataList) {
            NormalColumn sourceColumn = null;

            for (final NormalColumn normalColumn : source.getNormalColumns()) {
                if (normalColumn.getPhysicalName().equals(foreignKeyData.sourceColumnName)) {
                    sourceColumn = normalColumn;
                    break;
                }
            }

            if (sourceColumn == null) {
                return null;
            }

            if (!sourceColumn.isPrimaryKey()) {
                referenceForPK = false;
            }

            NormalColumn targetColumn = null;

            for (final NormalColumn normalColumn : target.getNormalColumns()) {
                if (normalColumn.getPhysicalName().equals(foreignKeyData.targetColumnName)) {
                    targetColumn = normalColumn;
                    break;
                }
            }

            if (targetColumn == null) {
                return null;
            }

            referenceMap.put(sourceColumn, targetColumn);
        }

        ComplexUniqueKey referencedComplexUniqueKey = null;
        NormalColumn referencedColumn = null;

        if (!referenceForPK) {
            if (referenceMap.size() > 1) {
                // TODO
                referencedComplexUniqueKey = new ComplexUniqueKey("");
                for (final NormalColumn column : referenceMap.keySet()) {
                    referencedComplexUniqueKey.addColumn(column);
                }
                // TODO
                source.getComplexUniqueKeyList().add(referencedComplexUniqueKey);

            } else {
                referencedColumn = referenceMap.keySet().iterator().next();
            }

        }

        final NormalColumn representedForeignKeyColumn = referenceMap.entrySet().iterator().next().getValue();

        final Relation relation = new Relation(referenceForPK, referencedComplexUniqueKey, referencedColumn, representedForeignKeyColumn.isNotNull(), representedForeignKeyColumn.isUniqueKey() || representedForeignKeyColumn.isSinglePrimaryKey());
        relation.setName(representativeData.name);
        relation.setSource(source);
        relation.setTargetWithoutForeignKey(target);

        String onUpdateAction = null;
        if (representativeData.updateRule == DatabaseMetaData.importedKeyCascade) {
            onUpdateAction = "CASCADE";
        } else if (representativeData.updateRule == DatabaseMetaData.importedKeyRestrict) {
            onUpdateAction = "RESTRICT";
        } else if (representativeData.updateRule == DatabaseMetaData.importedKeyNoAction) {
            onUpdateAction = "NO ACTION";
        } else if (representativeData.updateRule == DatabaseMetaData.importedKeySetDefault) {
            onUpdateAction = "SET DEFAULT";
        } else if (representativeData.updateRule == DatabaseMetaData.importedKeySetNull) {
            onUpdateAction = "SET NULL";
        } else {
            onUpdateAction = "";
        }

        relation.setOnUpdateAction(onUpdateAction);

        String onDeleteAction = null;
        if (representativeData.deleteRule == DatabaseMetaData.importedKeyCascade) {
            onDeleteAction = "CASCADE";
        } else if (representativeData.deleteRule == DatabaseMetaData.importedKeyRestrict) {
            onDeleteAction = "RESTRICT";
        } else if (representativeData.deleteRule == DatabaseMetaData.importedKeyNoAction) {
            onDeleteAction = "NO ACTION";
        } else if (representativeData.deleteRule == DatabaseMetaData.importedKeySetDefault) {
            onDeleteAction = "SET DEFAULT";
        } else if (representativeData.deleteRule == DatabaseMetaData.importedKeySetNull) {
            onDeleteAction = "SET NULL";
        } else {
            onDeleteAction = "";
        }

        relation.setOnDeleteAction(onDeleteAction);

        for (final Map.Entry<NormalColumn, NormalColumn> entry : referenceMap.entrySet()) {
            entry.getValue().addReference(entry.getKey(), relation);
        }

        return relation;
    }

    @Override
    public List<ERTable> getImportedTables() {
        return importedTables;
    }
    
    @Override
    public boolean isMergeTables() {
        return mergeTables;
    }

    public List<Sequence> getImportedSequences() {
        return importedSequences;
    }

    public List<View> getImportedViews() {
        return importedViews;
    }

    private List<View> importViews(final List<DBObject> dbObjectList, final ProgressMonitor monitor) throws SQLException, InterruptedException {
        final List<View> list = new ArrayList<View>();

        for (final Iterator<DBObject> iter = dbObjectList.iterator(); iter.hasNext();) {
            final DBObject dbObject = iter.next();

            if (DBObject.TYPE_VIEW.equals(dbObject.getType())) {
                iter.remove();
                taskCount++;

                final String name = dbObject.getName();
                final String schema = dbObject.getSchema();
                final String nameWithSchema = dbSetting.getTableNameWithSchema(name, schema);

                monitor.subTask("(" + taskCount + "/" + taskTotalCount + ") [" + dbObject.getType().toUpperCase() + "] " + nameWithSchema);
                monitor.worked(1);

                final View view = importView(schema, name);

                if (view != null) {
                    list.add(view);
                }
            }

            if (monitor.isCanceled()) {
                throw new InterruptedException("Cancel has been requested.");
            }
        }

        return list;
    }

    protected View importView(final String schema, final String viewName) throws SQLException {
        PreparedStatement stmt = null;
        ResultSet rs = null;

        final String sql = getViewDefinitionSQL(schema);
        if (sql == null) {
            return null;
        }

        try {
            stmt = con.prepareStatement(sql);

            if (schema != null) {
                stmt.setString(1, schema);
                stmt.setString(2, viewName);

            } else {
                stmt.setString(1, viewName);

            }

            rs = stmt.executeQuery();

            if (rs.next()) {
                final View view = new View();

                view.setPhysicalName(viewName);
                view.setLogicalName(translationResources.translate(viewName));
                final String definitionSQL = rs.getString(1);
                view.setSql(definitionSQL);
                view.getTableViewProperties().setSchema(schema);

                final List<Column> columnList = getViewColumnList(definitionSQL);
                view.setColumns(columnList);

                return view;
            }

            return null;

        } finally {
            this.close(rs);
            this.close(stmt);
        }
    }

    protected abstract String getViewDefinitionSQL(String schema);

    private List<Column> getViewColumnList(String sql) {
        final List<Column> columnList = new ArrayList<Column>();

        sql = sql.replaceAll("\\s+", " ");
        final String upperSql = sql.toUpperCase();

        final int selectIndex = upperSql.indexOf("SELECT ");
        final int fromIndex = upperSql.indexOf(" FROM ");

        if (selectIndex == -1) {
            return null;
        }

        String columnsPart = null;
        String fromPart = null;

        if (fromIndex != -1) {
            columnsPart = sql.substring(selectIndex + "SELECT ".length(), fromIndex);
            fromPart = sql.substring(fromIndex + " FROM ".length());

        } else {
            columnsPart = sql.substring(selectIndex + "SELECT ".length());
            fromPart = "";
        }

        final int whereIndex = fromPart.toUpperCase().indexOf(" WHERE ");

        if (whereIndex != -1) {
            fromPart = fromPart.substring(0, whereIndex);
        }

        final Map<String, String> aliasTableMap = new HashMap<String, String>();

        final StringTokenizer fromTokenizer = new StringTokenizer(fromPart, ",");

        while (fromTokenizer.hasMoreTokens()) {
            String tableName = fromTokenizer.nextToken().trim();

            tableName.replaceAll(" AS", "");
            tableName.replaceAll(" as", "");
            tableName.replaceAll(" As", "");
            tableName.replaceAll(" aS", "");

            String tableAlias = null;

            final int asIndex = tableName.toUpperCase().indexOf(" ");
            if (asIndex != -1) {
                tableAlias = tableName.substring(asIndex + 1).trim();
                tableName = tableName.substring(0, asIndex).trim();

                // TODO schema
                final int dotIndex = tableName.indexOf(".");
                if (dotIndex != -1) {
                    tableName = tableName.substring(dotIndex + 1);
                }

                aliasTableMap.put(tableAlias, tableName);
            }
        }

        final StringTokenizer columnTokenizer = new StringTokenizer(columnsPart, ",");

        String previousColumn = null;

        while (columnTokenizer.hasMoreTokens()) {
            String columnName = columnTokenizer.nextToken();

            if (previousColumn != null) {
                columnName = previousColumn + "," + columnName;
                previousColumn = null;
            }

            if (columnName.split("\\(").length > columnName.split("\\)").length) {
                previousColumn = columnName;
                continue;
            }

            columnName = columnName.trim();
            columnName = columnName.replaceAll("\"", "");

            String columnAlias = null;

            final Matcher matcher = AS_PATTERN.matcher(columnName);

            if (matcher.matches()) {
                columnAlias = matcher.toMatchResult().group(2).trim();
                columnName = matcher.toMatchResult().group(1).trim();

            } else {
                final int asIndex = columnName.indexOf(" ");
                if (asIndex != -1) {
                    columnAlias = columnName.substring(asIndex + 1).trim();
                    columnName = columnName.substring(0, asIndex).trim();
                }
            }

            int dotIndex = columnName.indexOf(".");

            String tableName = null;

            if (dotIndex != -1) {
                String aliasTableName = columnName.substring(0, dotIndex);
                columnName = columnName.substring(dotIndex + 1);

                dotIndex = columnName.indexOf(".");
                if (dotIndex != -1) {
                    aliasTableName = columnName.substring(0, dotIndex);
                    columnName = columnName.substring(dotIndex + 1);
                }

                tableName = aliasTableMap.get(aliasTableName);

                if (tableName == null) {
                    tableName = aliasTableName;
                }
            }

            if (columnAlias == null) {
                columnAlias = columnName;
            }

            NormalColumn targetColumn = null;

            if (columnName != null) {
                if (tableName != null) {
                    tableName = tableName.toLowerCase();
                }
                columnName = columnName.toLowerCase();

                if (!"*".equals(columnName)) {
                    for (final ERTable table : importedTables) {
                        if (tableName == null || (table.getPhysicalName() != null && tableName.equals(table.getPhysicalName().toLowerCase()))) {
                            for (final NormalColumn column : table.getExpandedColumns()) {
                                if (column.getPhysicalName() != null && columnName.equals(column.getPhysicalName().toLowerCase())) {
                                    targetColumn = column;

                                    break;
                                }
                            }

                            if (targetColumn != null) {
                                break;
                            }
                        }

                    }

                    try {
                        addColumnToView(columnList, targetColumn, columnAlias);
                    } catch (final NullPointerException e) {
                        throw e;
                    }
                } else {
                    for (final ERTable table : importedTables) {
                        if (tableName == null || (table.getPhysicalName() != null && tableName.equals(table.getPhysicalName().toLowerCase()))) {
                            for (final NormalColumn column : table.getExpandedColumns()) {
                                addColumnToView(columnList, column, null);
                            }
                        }
                    }
                }
            }
        }

        return columnList;
    }

    private void addColumnToView(final List<Column> columnList, NormalColumn targetColumn, final String columnAlias) {
        Word word = null;

        if (targetColumn != null) {
            while ((word = targetColumn.getWord()) == null) {
                targetColumn = targetColumn.getReferencedColumnList().get(0);
            }

            word = new Word(word);
            if (columnAlias != null) {
                word.setPhysicalName(columnAlias);
            }

        } else {
            word = new Word(columnAlias, translationResources.translate(columnAlias), null, new TypeData(null, null, false, null, false, false, false, null, false), null, null);

        }

        dictionary.getUniqueWord(word);

        final NormalColumn column = new NormalColumn(word, false, false, false, false, null, null, null, null, null);
        columnList.add(column);
    }

    public List<Tablespace> getImportedTablespaces() {
        return importedTablespaces;
    }

    private List<Tablespace> importTablespaces(final List<DBObject> dbObjectList, final ProgressMonitor monitor) throws SQLException, InterruptedException {
        final List<Tablespace> list = new ArrayList<Tablespace>();

        for (final Iterator<DBObject> iter = dbObjectList.iterator(); iter.hasNext();) {
            final DBObject dbObject = iter.next();

            if (DBObject.TYPE_TABLESPACE.equals(dbObject.getType())) {
                iter.remove();
                taskCount++;

                final String name = dbObject.getName();
                final String schema = dbObject.getSchema();
                final String nameWithSchema = dbSetting.getTableNameWithSchema(name, schema);

                monitor.subTask("(" + taskCount + "/" + taskTotalCount + ") [" + dbObject.getType().toUpperCase() + "] " + nameWithSchema);
                monitor.worked(1);

                final Tablespace tablespace = importTablespace(name);

                if (tablespace != null) {
                    list.add(tablespace);
                }
            }

            if (monitor.isCanceled()) {
                throw new InterruptedException("Cancel has been requested.");
            }
        }

        return list;
    }

    public List<Trigger> getImportedTriggers() {
        return importedTriggers;
    }

    protected Tablespace importTablespace(final String tablespaceName) throws SQLException {
        // TODO
        return null;
    }

    public Exception getException() {
        return exception;
    }

    public static void main(final String[] args) throws InputException, InstantiationException, IllegalAccessException, SQLException {
        new ERDiagramActivator();

        final DBSetting setting = new DBSetting("Oracle", "localhost", 1521, "XE", "nakajima", "nakajima", true, null, null);

        Connection con = null;
        try {
            con = setting.connect();
            final DatabaseMetaData metaData = con.getMetaData();

            metaData.getIndexInfo(null, "SYS", "ALERT_QT", false, false);

        } finally {
            if (con != null) {
                con.close();
            }
        }
    }

    protected void close(final ResultSet rs) throws SQLException {
        if (rs != null) {
            rs.close();
        }
    }

    protected void close(final Statement stmt) throws SQLException {
        if (stmt != null) {
            stmt.close();
        }
    }

}
