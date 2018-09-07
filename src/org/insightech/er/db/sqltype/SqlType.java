package org.insightech.er.db.sqltype;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.insightech.er.db.impl.oracle.OracleDBManager;

public class SqlType implements Serializable {

    private static final long serialVersionUID = -8273043043893517634L;

    public static final String SQL_TYPE_ID_SERIAL = "serial";

    public static final String SQL_TYPE_ID_BIG_SERIAL = "bigserial";

    public static final String SQL_TYPE_ID_INTEGER = "integer";

    public static final String SQL_TYPE_ID_BIG_INT = "bigint";

    public static final String SQL_TYPE_ID_CHAR = "character";

    public static final String SQL_TYPE_ID_VARCHAR = "varchar";

    public static final Pattern NEED_LENGTH_PATTERN = Pattern.compile(".+\\([a-zA-Z][,\\)].*");

    public static final Pattern NEED_DECIMAL_PATTERN1 = Pattern.compile(".+\\([a-zA-Z],[a-zA-Z]\\)");

    public static final Pattern NEED_DECIMAL_PATTERN2 = Pattern.compile(".+\\([a-zA-Z]\\).*\\([a-zA-Z]\\)");

    private static final List<SqlType> SQL_TYPE_LIST = new ArrayList<SqlType>();

    private final String name;

    private final Class javaClass;

    private final boolean needArgs;

    boolean fullTextIndexable;

    private static Map<String, Map<TypeKey, SqlType>> dbSqlTypeMap = new HashMap<String, Map<TypeKey, SqlType>>();

    private static Map<String, Map<SqlType, String>> dbSqlTypeToAliasMap = new HashMap<String, Map<SqlType, String>>();

    private static Map<String, Map<String, SqlType>> dbAliasToSqlTypeMap = new HashMap<String, Map<String, SqlType>>();

    static {
        try {
            SqlTypeFactory.load();

        } catch (final Exception e) {
            e.printStackTrace();
            throw new ExceptionInInitializerError(e);
        }
    }

    public static class TypeKey {
        private final String alias;

        private int size;

        private final int decimal;

        public TypeKey(String alias, final int size, final int decimal) {
            if (alias != null) {
                alias = alias.toUpperCase();
            }

            this.alias = alias;

            if (size == Integer.MAX_VALUE) {
                this.size = 0;
            } else {
                this.size = size;
            }

            this.decimal = decimal;
        }

        @Override
        public boolean equals(final Object obj) {
            final TypeKey other = (TypeKey) obj;

            if (alias == null) {
                if (other.alias == null) {
                    if (size == other.size && decimal == other.decimal) {
                        return true;
                    }
                    return false;

                } else {
                    return false;
                }

            } else {
                if (alias.equals(other.alias) && size == other.size && decimal == other.decimal) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public int hashCode() {
            if (alias == null) {
                return (size * 10) + decimal;
            }
            return (alias.hashCode() * 100) + (size * 10) + decimal;
        }

        @Override
        public String toString() {
            return "TypeKey [alias=" + alias + ", size=" + size + ", decimal=" + decimal + "]";
        }

    }

    public SqlType(final String name, final Class javaClass, final boolean needArgs, final boolean fullTextIndexable) {
        this.name = name;
        this.javaClass = javaClass;
        this.needArgs = needArgs;
        this.fullTextIndexable = fullTextIndexable;

        SQL_TYPE_LIST.add(this);
    }

    public static void setDBAliasMap(final Map<String, Map<SqlType, String>> dbSqlTypeToAliasMap, final Map<String, Map<String, SqlType>> dbAliasToSqlTypeMap, final Map<String, Map<TypeKey, SqlType>> dbSqlTypeMap) {
        SqlType.dbSqlTypeMap = dbSqlTypeMap;
        SqlType.dbSqlTypeToAliasMap = dbSqlTypeToAliasMap;
        SqlType.dbAliasToSqlTypeMap = dbAliasToSqlTypeMap;
    }

    public void addToSqlTypeMap(final TypeKey typeKey, final String database) {
        final Map<TypeKey, SqlType> sqlTypeMap = dbSqlTypeMap.get(database);
        sqlTypeMap.put(typeKey, this);
    }
    
    public static Map<TypeKey, SqlType> getSqlTypeMapByDatabase(final String database) {
    	return dbSqlTypeMap.get(database);
    }

    public String getId() {
        return name;
    }

    public Class getJavaClass() {
        return javaClass;
    }

    public boolean doesNeedArgs() {
        return needArgs;
    }

    public boolean isFullTextIndexable() {
        return fullTextIndexable;
    }

    public static List<SqlType> getAllSqlType() {
        return SQL_TYPE_LIST;
    }

    public static SqlType valueOf(final String database, final String alias) {
        return dbAliasToSqlTypeMap.get(database).get(alias);
    }

    public static SqlType valueOf(final String database, final String alias, int size, int decimal) {
        if (alias == null) {
            return null;
        }

        final Map<TypeKey, SqlType> sqlTypeMap = dbSqlTypeMap.get(database);

        TypeKey typeKey = new TypeKey(alias, size, decimal);
        SqlType sqlType = sqlTypeMap.get(typeKey);

        if (sqlType != null) {
            return sqlType;
        }

        if (decimal > 0) {
            decimal = -1;

            typeKey = new TypeKey(alias, size, decimal);
            sqlType = sqlTypeMap.get(typeKey);

            if (sqlType != null) {
                return sqlType;
            }
        }

        if (size > 0) {
            size = -1;

            typeKey = new TypeKey(alias, size, decimal);
            sqlType = sqlTypeMap.get(typeKey);

            if (sqlType != null) {
                return sqlType;
            }
        }

        typeKey = new TypeKey(alias, 0, 0);
        sqlType = sqlTypeMap.get(typeKey);

        return sqlType;
    }

    public static SqlType valueOfId(final String id) {
        SqlType sqlType = null;

        if (id == null) {
            return null;
        }

        for (final SqlType type : SQL_TYPE_LIST) {
            if (id.equals(type.getId())) {
                sqlType = type;
            }
        }
        return sqlType;
    }

    public boolean isNeedLength(final String database) {
        final String alias = getAlias(database);
        if (alias == null) {
            return false;
        }

        final Matcher matcher = NEED_LENGTH_PATTERN.matcher(alias);

        if (matcher.matches()) {
            return true;
        }

        return false;
    }

    public boolean isNeedDecimal(final String database) {
        final String alias = getAlias(database);
        if (alias == null) {
            return false;
        }

        Matcher matcher = NEED_DECIMAL_PATTERN1.matcher(alias);

        if (matcher.matches()) {
            return true;
        }

        matcher = NEED_DECIMAL_PATTERN2.matcher(alias);

        if (matcher.matches()) {
            return true;
        }

        return false;
    }

    public boolean isNeedCharSemantics(final String database) {
        if (!OracleDBManager.ID.equals(database)) {
            return false;
        }

        if (name.startsWith(SQL_TYPE_ID_CHAR) || name.startsWith(SQL_TYPE_ID_VARCHAR)) {
            return true;
        }

        return false;
    }

    public boolean isTimestamp() {
        if (javaClass == Date.class) {
            return true;
        }

        return false;
    }

    public boolean isNumber() {
        if (Number.class.isAssignableFrom(javaClass)) {
            return true;
        }

        return false;
    }

    public static List<String> getAliasList(final String database) {
        final Map<SqlType, String> aliasMap = dbSqlTypeToAliasMap.get(database);

        final Set<String> aliases = new LinkedHashSet<String>();

        for (final Entry<SqlType, String> entry : aliasMap.entrySet()) {
            final String alias = entry.getValue();
            aliases.add(alias);
        }

        final List<String> list = new ArrayList<String>(aliases);

        Collections.sort(list);

        return list;
    }

    public String getAlias(final String database) {
        final Map<SqlType, String> aliasMap = dbSqlTypeToAliasMap.get(database);

        return aliasMap.get(this);
    }

    public boolean isUnsupported(final String database) {
        final String alias = getAlias(database);

        if (alias == null) {
            return true;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }

        if (!(obj instanceof SqlType)) {
            return false;
        }

        final SqlType type = (SqlType) obj;

        return name.equals(type.name);
    }
    
	public String getName() {
		return name;
	}

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return getId();
    }
}
