/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2005-2011, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package legacy.org.postgresql.jdbc2;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Collections;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;

import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PGobject;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;
import legacy.org.postgresql.core.Oid;
import legacy.org.postgresql.core.BaseStatement;
import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.core.QueryExecutor;
import legacy.org.postgresql.core.TypeInfo;

public class TypeInfoCache implements TypeInfo {

    // pgname (String) -> java.sql.Types (Integer)
    private Map _pgNameToSQLType;

    // pgname (String) -> java class name (String)
    // ie "text" -> "java.lang.String"
    private Map _pgNameToJavaClass;

    // oid (Integer) -> pgname (String)
    private Map _oidToPgName;
    // pgname (String) -> oid (Integer)
    private Map _pgNameToOid;

    // pgname (String) -> extension pgobject (Class)
    private Map _pgNameToPgObject;

    // type array oid -> base type's oid
    private Map/*<Integer, Integer>*/ _pgArrayToPgType;

    // array type oid -> base type array element delimiter
    private Map/*<Integer, Character>*/ _arrayOidToDelimiter;

    private BaseConnection _conn;
    private final int _unknownLength;
    private PreparedStatement _getOidStatement;
    private PreparedStatement _getNameStatement;
    private PreparedStatement _getArrayElementOidStatement;
    private PreparedStatement _getArrayDelimiterStatement;
    private PreparedStatement _getTypeInfoStatement;

    // basic pg types info:
    // 0 - type name
    // 1 - type oid
    // 2 - sql type
    // 3 - java class
    // 4 - array type oid
    // 5 - conditional minimum server version
    // 6 - conditional minimum JDK build version
    private static final Object types[][] = {
        {"int2", new Integer(Oid.INT2), new Integer(Types.SMALLINT), "java.lang.Integer", new Integer(Oid.INT2_ARRAY)},
        {"int4", new Integer(Oid.INT4), new Integer(Types.INTEGER), "java.lang.Integer", new Integer(Oid.INT4_ARRAY)},
        {"oid", new Integer(Oid.OID), new Integer(Types.BIGINT), "java.lang.Long", new Integer(Oid.OID_ARRAY)},
        {"int8", new Integer(Oid.INT8), new Integer(Types.BIGINT), "java.lang.Long", new Integer(Oid.INT8_ARRAY)},
        {"money", new Integer(Oid.MONEY), new Integer(Types.DOUBLE), "java.lang.Double", new Integer(Oid.MONEY_ARRAY)},
        {"numeric", new Integer(Oid.NUMERIC), new Integer(Types.NUMERIC), "java.math.BigDecimal", new Integer(Oid.NUMERIC_ARRAY)},
        {"float4", new Integer(Oid.FLOAT4), new Integer(Types.REAL), "java.lang.Float", new Integer(Oid.FLOAT4_ARRAY)},
        {"float8", new Integer(Oid.FLOAT8), new Integer(Types.DOUBLE), "java.lang.Double", new Integer(Oid.FLOAT8_ARRAY)},
        {"char", new Integer(Oid.CHAR), new Integer(Types.CHAR), "java.lang.String", new Integer(Oid.CHAR_ARRAY)},
        {"bpchar", new Integer(Oid.BPCHAR), new Integer(Types.CHAR), "java.lang.String", new Integer(Oid.BPCHAR_ARRAY)},
        {"varchar", new Integer(Oid.VARCHAR), new Integer(Types.VARCHAR), "java.lang.String", new Integer(Oid.VARCHAR_ARRAY)},
        {"text", new Integer(Oid.TEXT), new Integer(Types.VARCHAR), "java.lang.String", new Integer(Oid.TEXT_ARRAY)},
        {"name", new Integer(Oid.NAME), new Integer(Types.VARCHAR), "java.lang.String", new Integer(Oid.NAME_ARRAY)},
        {"bytea", new Integer(Oid.BYTEA), new Integer(Types.BINARY), "[B", new Integer(Oid.BYTEA_ARRAY)},
        {"bool", new Integer(Oid.BOOL), new Integer(Types.BIT), "java.lang.Boolean", new Integer(Oid.BOOL_ARRAY)},
        {"bit", new Integer(Oid.BIT), new Integer(Types.BIT), "java.lang.Boolean", new Integer(Oid.BIT_ARRAY)},
        {"date", new Integer(Oid.DATE), new Integer(Types.DATE), "java.sql.Date", new Integer(Oid.DATE_ARRAY)},
        {"time", new Integer(Oid.TIME), new Integer(Types.TIME), "java.sql.Time", new Integer(Oid.TIME_ARRAY)},
        {"timetz", new Integer(Oid.TIMETZ), new Integer(Types.TIME), "java.sql.Time", new Integer(Oid.TIMETZ_ARRAY)},
        {"timestamp", new Integer(Oid.TIMESTAMP), new Integer(Types.TIMESTAMP), "java.sql.Timestamp", new Integer(Oid.TIMESTAMP_ARRAY)},
        {"timestamptz", new Integer(Oid.TIMESTAMPTZ), new Integer(Types.TIMESTAMP), "java.sql.Timestamp", new Integer(Oid.TIMESTAMPTZ_ARRAY)},
    };

    /**
     * PG maps several alias to real type names.  When we do queries
     * against pg_catalog, we must use the real type, not an alias, so
     * use this mapping.
     */
    private final static HashMap typeAliases;
    static {
        typeAliases = new HashMap();
        typeAliases.put("smallint", "int2");
        typeAliases.put("integer", "int4");
        typeAliases.put("int", "int4");
        typeAliases.put("bigint", "int8");
        typeAliases.put("float", "float8");
        typeAliases.put("boolean", "bool");
        typeAliases.put("decimal", "numeric");
    }

    public TypeInfoCache(BaseConnection conn, int unknownLength)
    {
        _conn = conn;
        _unknownLength = unknownLength;
        _oidToPgName = new HashMap();
        _pgNameToOid = new HashMap();
        _pgNameToJavaClass = new HashMap();
        _pgNameToPgObject = new HashMap();
        _pgArrayToPgType = new HashMap();
        _arrayOidToDelimiter = new HashMap();

        // needs to be synchronized because the iterator is returned
        // from getPGTypeNamesWithSQLTypes()
        _pgNameToSQLType = Collections.synchronizedMap(new HashMap());

        for (int i=0; i<types.length; i++) {
            String pgTypeName = (String)types[i][0];
            Integer oid = (Integer)types[i][1];
            Integer sqlType = (Integer)types[i][2];
            String javaClass = (String)types[i][3];
            Integer arrayOid = (Integer)types[i][4];

            addCoreType(pgTypeName, oid, sqlType, javaClass, arrayOid);
        }

    }

    public synchronized void addCoreType(String pgTypeName, Integer oid, Integer sqlType, String javaClass, Integer arrayOid)
    {
        _pgNameToJavaClass.put(pgTypeName, javaClass);
        _pgNameToOid.put(pgTypeName, oid);
        _oidToPgName.put(oid, pgTypeName);
        _pgArrayToPgType.put(arrayOid, oid);
        _pgNameToSQLType.put(pgTypeName, sqlType);

        // Currently we hardcode all core types array delimiter
        // to a comma.  In a stock install the only exception is
        // the box datatype and it's not a JDBC core type.
        //
        Character delim = new Character(',');
        _arrayOidToDelimiter.put(oid, delim);

        String pgArrayTypeName = "_" + pgTypeName;
        _pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
        _pgNameToSQLType.put(pgArrayTypeName, new Integer(Types.ARRAY));
    }


    public synchronized void addDataType(String type, Class klass) throws SQLException
    {
        if (!PGobject.class.isAssignableFrom(klass))
            throw new PSQLException(GT.tr("The class {0} does not implement org.postgresql.util.PGobject.", klass.toString()), PSQLState.INVALID_PARAMETER_TYPE);

        _pgNameToPgObject.put(type, klass);
        _pgNameToJavaClass.put(type, klass.getName());
    }

    public Iterator getPGTypeNamesWithSQLTypes()
    {
        return _pgNameToSQLType.keySet().iterator();
    }

    public int getSQLType(int oid) throws SQLException
    {
        return getSQLType(getPGType(oid));
    }

    public synchronized int getSQLType(String pgTypeName) throws SQLException
    {
        Integer i = (Integer)_pgNameToSQLType.get(pgTypeName);
        if (i != null)
            return i.intValue();

        if (_getTypeInfoStatement == null) {
            // There's no great way of telling what's an array type.
            // People can name their own types starting with _.
            // Other types use typelem that aren't actually arrays, like box.
            //
            String sql = "SELECT typinput='array_in'::regproc, typtype FROM ";
            if (_conn.haveMinimumServerVersion("7.3")) {
                sql += "pg_catalog.";
            }
            sql += "pg_type WHERE typname = ?";

            _getTypeInfoStatement = _conn.prepareStatement(sql);
        }

        _getTypeInfoStatement.setString(1, pgTypeName);

        // Go through BaseStatement to avoid transaction start.
        if (!((BaseStatement)_getTypeInfoStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        ResultSet rs = _getTypeInfoStatement.getResultSet();

        Integer type = null;
        if (rs.next()) {
            boolean isArray = rs.getBoolean(1);
            String typtype = rs.getString(2);
            if (isArray) {
                type = new Integer(Types.ARRAY);
            } else if ("c".equals(typtype)) {
                type = new Integer(Types.STRUCT);
            } else if ("d".equals(typtype)) {
                type = new Integer(Types.DISTINCT);
            }
        }

        if (type == null) {
             type = new Integer(Types.OTHER);
        }
        rs.close();

        _pgNameToSQLType.put(pgTypeName, type);

        return type.intValue();
    }

    public synchronized int getPGType(String pgTypeName) throws SQLException
    {
        Integer oid = (Integer)_pgNameToOid.get(pgTypeName);
        if (oid != null)
            return oid.intValue();

        if (_getOidStatement == null) {
            String sql;
            if (_conn.haveMinimumServerVersion("7.3")) {
                sql = "SELECT oid FROM pg_catalog.pg_type WHERE typname = ?";
            } else {
                sql = "SELECT oid FROM pg_type WHERE typname = ?";
            }

            _getOidStatement = _conn.prepareStatement(sql);
        }

        _getOidStatement.setString(1, pgTypeName);

        // Go through BaseStatement to avoid transaction start.
        if (!((BaseStatement)_getOidStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        oid = new Integer(Oid.UNSPECIFIED);
        ResultSet rs = _getOidStatement.getResultSet();
        if (rs.next()) {
            oid = new Integer((int)rs.getLong(1));
            _oidToPgName.put(oid, pgTypeName);
        }
        _pgNameToOid.put(pgTypeName, oid);
        rs.close();

        return oid.intValue();
    }

    public synchronized String getPGType(int oid) throws SQLException
    {
        if (oid == Oid.UNSPECIFIED)
            return null;

        String pgTypeName = (String)_oidToPgName.get(new Integer(oid));
        if (pgTypeName != null)
            return pgTypeName;

        if (_getNameStatement == null) {
            String sql;
            if (_conn.haveMinimumServerVersion("7.3")) {
                sql = "SELECT typname FROM pg_catalog.pg_type WHERE oid = ?";
            } else {
                sql = "SELECT typname FROM pg_type WHERE oid = ?";
            }

            _getNameStatement = _conn.prepareStatement(sql);
        }

        _getNameStatement.setInt(1, oid);

        // Go through BaseStatement to avoid transaction start.
        if (!((BaseStatement)_getNameStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        ResultSet rs = _getNameStatement.getResultSet();
        if (rs.next()) {
            pgTypeName = rs.getString(1);
            _pgNameToOid.put(pgTypeName, new Integer(oid));
            _oidToPgName.put(new Integer(oid), pgTypeName);
        }
        rs.close();

        return pgTypeName;
    }

    public int getPGArrayType(String elementTypeName) throws SQLException
    {
        elementTypeName = getTypeForAlias(elementTypeName);
        return getPGType("_" + elementTypeName);
    }

    /**
     * Return the oid of the array's base element if it's an array,
     * if not return the provided oid.  This doesn't do any database
     * lookups, so it's only useful for the originally provided type
     * mappings.  This is fine for it's intended uses where we only have
     * intimate knowledge of types that are already known to the driver.
     */
    protected synchronized int convertArrayToBaseOid(int oid)
    {
        Integer i = (Integer)_pgArrayToPgType.get(new Integer(oid));
        if (i == null)
            return oid;
        return i.intValue();
    }

    public synchronized char getArrayDelimiter(int oid) throws SQLException
    {
        if (oid == Oid.UNSPECIFIED)
            return ',';

        Character delim = (Character) _arrayOidToDelimiter.get(new Integer(oid));
        if (delim != null)
            return delim.charValue();

        if (_getArrayDelimiterStatement == null) {
            String sql;
            if (_conn.haveMinimumServerVersion("7.3")) {
                sql = "SELECT e.typdelim FROM pg_catalog.pg_type t, pg_catalog.pg_type e WHERE t.oid = ? and t.typelem = e.oid";
            } else {
                sql = "SELECT e.typdelim FROM pg_type t, pg_type e WHERE t.oid = ? and t.typelem = e.oid";
            }
            _getArrayDelimiterStatement = _conn.prepareStatement(sql);
        }

        _getArrayDelimiterStatement.setInt(1, oid);

        // Go through BaseStatement to avoid transaction start.
        if (!((BaseStatement) _getArrayDelimiterStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        ResultSet rs = _getArrayDelimiterStatement.getResultSet();
        if (!rs.next())
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        String s = rs.getString(1);
        delim = new Character(s.charAt(0));

        _arrayOidToDelimiter.put(new Integer(oid), delim);

        rs.close();

        return delim.charValue();
    }

    public synchronized int getPGArrayElement (int oid) throws SQLException
    {
        if (oid == Oid.UNSPECIFIED)
            return Oid.UNSPECIFIED;

        Integer pgType = (Integer) _pgArrayToPgType.get(new Integer(oid));

        if (pgType != null)
            return pgType.intValue();

        if (_getArrayElementOidStatement == null) {
            String sql;
            if (_conn.haveMinimumServerVersion("7.3")) {
                sql = "SELECT e.oid, e.typname FROM pg_catalog.pg_type t, pg_catalog.pg_type e WHERE t.oid = ? and t.typelem = e.oid";
            } else {
                sql = "SELECT e.oid, e.typname FROM pg_type t, pg_type e WHERE t.oid = ? and t.typelem = e.oid";
            }
            _getArrayElementOidStatement = _conn.prepareStatement(sql);
        }

        _getArrayElementOidStatement.setInt(1, oid);

        // Go through BaseStatement to avoid transaction start.
        if (!((BaseStatement) _getArrayElementOidStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        ResultSet rs = _getArrayElementOidStatement.getResultSet();
        if (!rs.next())
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        pgType = new Integer((int)rs.getLong(1));
        _pgArrayToPgType.put(new Integer(oid), pgType);
        _pgNameToOid.put(rs.getString(2), pgType);
        _oidToPgName.put(pgType, rs.getString(2));

        rs.close();

        return pgType.intValue();
    }

    public synchronized Class getPGobject(String type)
    {
        return (Class)_pgNameToPgObject.get(type);
    }

    public synchronized String getJavaClass(int oid) throws SQLException
    {
        String pgTypeName = getPGType(oid);

        String result = (String)_pgNameToJavaClass.get(pgTypeName);
        if (result != null) {
            return result;
        }

        if (getSQLType(pgTypeName) == Types.ARRAY) {
            result = "java.sql.Array";
            _pgNameToJavaClass.put(pgTypeName, result);
        }

        return result;
    }

    public String getTypeForAlias(String alias) {
        String type = (String) typeAliases.get(alias);
        if (type != null)
            return type;
        return alias;
    }

    public int getPrecision(int oid, int typmod) {
        oid = convertArrayToBaseOid(oid);
        switch (oid) {
            case Oid.INT2:
                return 5;

            case Oid.OID:
            case Oid.INT4:
                return 10;

            case Oid.INT8:
                return 19;

            case Oid.FLOAT4:
                // For float4 and float8, we can normally only get 6 and 15
                // significant digits out, but extra_float_digits may raise
                // that number by up to two digits.
                return 8;

            case Oid.FLOAT8:
                return 17;

            case Oid.NUMERIC:
                if (typmod == -1)
                    return 0;
                return ((typmod-4) & 0xFFFF0000) >> 16;

            case Oid.CHAR:
            case Oid.BOOL:
                return 1;

            case Oid.BPCHAR:
            case Oid.VARCHAR:
                if (typmod == -1)
                    return _unknownLength;
                return typmod - 4;

            // datetime types get the
            // "length in characters of the String representation"
            case Oid.DATE:
            case Oid.TIME:
            case Oid.TIMETZ:
            case Oid.INTERVAL:
            case Oid.TIMESTAMP:
            case Oid.TIMESTAMPTZ:
                return getDisplaySize(oid, typmod);

            case Oid.BIT:
                return typmod;

            case Oid.VARBIT:
                if (typmod == -1)
                    return _unknownLength;
                return typmod;

            case Oid.TEXT:
            case Oid.BYTEA:
            default:
                return _unknownLength;
        }
    }

    public int getScale(int oid, int typmod) {
        oid = convertArrayToBaseOid(oid);
        switch(oid) {
            case Oid.FLOAT4:
                return 8;
            case Oid.FLOAT8:
                return 17;
            case Oid.NUMERIC:
                if (typmod == -1)
                    return 0;
                return (typmod-4) & 0xFFFF;
            case Oid.TIME:
            case Oid.TIMETZ:
            case Oid.TIMESTAMP:
            case Oid.TIMESTAMPTZ:
                if (typmod == -1)
                    return 6;
                return typmod;
            case Oid.INTERVAL:
                if (typmod == -1)
                    return 6;
                return typmod & 0xFFFF;
            default:
                return 0;
        }
    }

    public boolean isCaseSensitive(int oid) {
        oid = convertArrayToBaseOid(oid);
        switch(oid) {
            case Oid.OID:
            case Oid.INT2:
            case Oid.INT4:
            case Oid.INT8:
            case Oid.FLOAT4:
            case Oid.FLOAT8:
            case Oid.NUMERIC:
            case Oid.BOOL:
            case Oid.BIT:
            case Oid.VARBIT:
            case Oid.DATE:
            case Oid.TIME:
            case Oid.TIMETZ:
            case Oid.TIMESTAMP:
            case Oid.TIMESTAMPTZ:
            case Oid.INTERVAL:
                return false;
            default:
                return true;
        }
    }

    public boolean isSigned(int oid) {
        oid = convertArrayToBaseOid(oid);
        switch(oid) {
            case Oid.INT2:
            case Oid.INT4:
            case Oid.INT8:
            case Oid.FLOAT4:
            case Oid.FLOAT8:
            case Oid.NUMERIC:
                return true;
            default:
                return false;
        }
    }

    public int getDisplaySize(int oid, int typmod) {
        oid = convertArrayToBaseOid(oid);
        switch(oid) {
            case Oid.INT2:
                return 6; // -32768 to +32767
            case Oid.INT4:
                return 11; // -2147483648 to +2147483647
            case Oid.OID:
                return 10; // 0 to 4294967295
            case Oid.INT8:
                return 20; // -9223372036854775808 to +9223372036854775807
            case Oid.FLOAT4:
                // varies based upon the extra_float_digits GUC.
                // These values are for the longest possible length.
                return 15; // sign + 9 digits + decimal point + e + sign + 2 digits
            case Oid.FLOAT8:
                return 25; // sign + 18 digits + decimal point + e + sign + 3 digits
            case Oid.CHAR:
                return 1;
            case Oid.BOOL:
                return 1;
            case Oid.DATE:
                return 13; // "4713-01-01 BC" to  "01/01/4713 BC" - "31/12/32767"
            case Oid.TIME:
            case Oid.TIMETZ:
            case Oid.TIMESTAMP:
            case Oid.TIMESTAMPTZ:
                // Calculate the number of decimal digits + the decimal point.
                int secondSize;
                switch(typmod) {
                    case -1:
                        secondSize = 6 + 1;
                        break;
                    case 0:
                        secondSize = 0;
                        break;
                    case 1:
                        // Bizarrely SELECT '0:0:0.1'::time(1); returns 2 digits.
                        secondSize = 2 + 1;
                        break;
                    default:
                        secondSize = typmod + 1;
                        break;
                }

                // We assume the worst case scenario for all of these.
                // time = '00:00:00' = 8
                // date = '5874897-12-31' = 13 (although at large values second precision is lost)
                // date = '294276-11-20' = 12 --enable-integer-datetimes
                // zone = '+11:30' = 6;

                switch(oid) {
                    case Oid.TIME:
                        return 8 + secondSize;
                    case Oid.TIMETZ:
                        return 8 + secondSize + 6;
                    case Oid.TIMESTAMP:
                        return 13 + 1 + 8 + secondSize;
                    case Oid.TIMESTAMPTZ:
                        return 13 + 1 + 8 + secondSize + 6;
                }
            case Oid.INTERVAL:
                return 49; // SELECT LENGTH('-123456789 years 11 months 33 days 23 hours 10.123456 seconds'::interval);
            case Oid.VARCHAR:
            case Oid.BPCHAR:
                if (typmod == -1)
                    return _unknownLength;
                return typmod - 4;
            case Oid.NUMERIC:
                if (typmod == -1)
                    return 131089; // SELECT LENGTH(pow(10::numeric,131071)); 131071 = 2^17-1
                int precision = (typmod-4 >> 16) & 0xffff;
                int scale = (typmod-4) & 0xffff;
                // sign + digits + decimal point (only if we have nonzero scale)
                return 1 + precision + (scale != 0 ? 1 : 0);
            case Oid.BIT:
                return typmod;
            case Oid.VARBIT:
                if (typmod == -1)
                    return _unknownLength;
                return typmod;
            case Oid.TEXT:
            case Oid.BYTEA:
                return _unknownLength;
            default:
                return _unknownLength;
        }
    }

    public int getMaximumPrecision(int oid) {
        oid = convertArrayToBaseOid(oid);
        switch(oid) {
            case Oid.NUMERIC:
                return 1000;
            case Oid.TIME:
            case Oid.TIMETZ:
                // Technically this depends on the --enable-integer-datetimes
                // configure setting.  It is 6 with integer and 10 with float.
                return 6;
            case Oid.TIMESTAMP:
            case Oid.TIMESTAMPTZ:
            case Oid.INTERVAL:
                return 6;
            case Oid.BPCHAR:
            case Oid.VARCHAR:
                return 10485760;
            case Oid.BIT:
            case Oid.VARBIT:
                return 83886080;
            default:
                return 0;
        }
    }

    public boolean requiresQuoting(int oid) throws SQLException
    {
        int sqlType = getSQLType(oid);
        switch(sqlType) {
            case Types.BIGINT:
            case Types.DOUBLE:
            case Types.FLOAT:
            case Types.INTEGER:
            case Types.REAL:
            case Types.SMALLINT:
            case Types.TINYINT:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return false;
        }
        return true;
    }

}
