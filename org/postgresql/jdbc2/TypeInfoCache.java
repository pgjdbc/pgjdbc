/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2005, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/TypeInfoCache.java,v 1.7 2007/02/19 05:57:53 jurka Exp $
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.jdbc2;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Collections;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import org.postgresql.core.Oid;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.QueryExecutor;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLException;

public class TypeInfoCache {

    // pgname (String) -> java.sql.Types (Integer)
    private static final Map _pgNameToSQLType;

    // pgname (String) -> java class name (String)
    // ie "text" -> "java.lang.String"
    private Map _pgNameToJavaClass;

    // oid (Integer) -> pgname (String)
    private Map _oidToPgName;
    // pgname (String) -> oid (Integer)
    private Map _pgNameToOid;

    // pgname (String) -> extension pgobject (Class)
    private Map _pgNameToPgObject;

    private BaseConnection _conn;
    private PreparedStatement _getOidStatement;
    private PreparedStatement _getNameStatement;

    private static final Object types[][] = {
        {"int2", new Integer(Oid.INT2), new Integer(Types.SMALLINT), "java.lang.Integer"},
        {"int4", new Integer(Oid.INT4), new Integer(Types.INTEGER), "java.lang.Integer"},
        {"oid", new Integer(Oid.OID), new Integer(Types.BIGINT), "java.lang.Long"},
        {"int8", new Integer(Oid.INT8), new Integer(Types.BIGINT), "java.lang.Long"},
        {"money", new Integer(Oid.MONEY), new Integer(Types.DOUBLE), "java.lang.Double"},
        {"numeric", new Integer(Oid.NUMERIC), new Integer(Types.NUMERIC), "java.math.BigDecimal"},
        {"float4", new Integer(Oid.FLOAT4), new Integer(Types.REAL), "java.lang.Float"},
        {"float8", new Integer(Oid.FLOAT8), new Integer(Types.DOUBLE), "java.lang.Double"},
	{"char", new Integer(Oid.CHAR), new Integer(Types.CHAR), "java.lang.String"},
        {"bpchar", new Integer(Oid.BPCHAR), new Integer(Types.CHAR), "java.lang.String"},
        {"varchar", new Integer(Oid.VARCHAR), new Integer(Types.VARCHAR), "java.lang.String"},
        {"text", new Integer(Oid.TEXT), new Integer(Types.VARCHAR), "java.lang.String"},
        {"name", new Integer(Oid.NAME), new Integer(Types.VARCHAR), "java.lang.String"},
        {"bytea", new Integer(Oid.BYTEA), new Integer(Types.BINARY), "[B"},
        {"bool", new Integer(Oid.BOOL), new Integer(Types.BIT), "java.lang.Boolean"},
        {"bit", new Integer(Oid.BIT), new Integer(Types.BIT), "java.lang.Boolean"},
        {"date", new Integer(Oid.DATE), new Integer(Types.DATE), "java.sql.Date"},
        {"time", new Integer(Oid.TIME), new Integer(Types.TIME), "java.sql.Time"},
        {"timetz", new Integer(Oid.TIMETZ), new Integer(Types.TIME), "java.sql.Time"},
        {"timestamp", new Integer(Oid.TIMESTAMP), new Integer(Types.TIMESTAMP), "java.sql.Timestamp"},
        {"timestamptz", new Integer(Oid.TIMESTAMPTZ), new Integer(Types.TIMESTAMP), "java.sql.Timestamp"}
    };

    static
    {
        Map pgNameToSQLType = new HashMap();
        for (int i=0; i<types.length; i++) {
            pgNameToSQLType.put(types[i][0], types[i][2]);
            String arrayType = "_" + types[i][0];
            pgNameToSQLType.put(arrayType, new Integer(Types.ARRAY));
        }
        // needs to be unmodifiable because the iterator is returned
        // getPGTypeNamesWithSQLTypes() if the content of the map
        // should ever need to be modified, this could be changed
        // into a synchronizedMap()
        _pgNameToSQLType = Collections.unmodifiableMap(pgNameToSQLType);
    }

    public TypeInfoCache(BaseConnection conn)
    {
        _conn = conn;
        _oidToPgName = new HashMap();
        _pgNameToOid = new HashMap();
        _pgNameToJavaClass = new HashMap();
        _pgNameToPgObject = new HashMap();

        for (int i=0; i<types.length; i++) {
            _pgNameToJavaClass.put(types[i][0], types[i][3]);
            _pgNameToOid.put(types[i][0], types[i][1]);
            _oidToPgName.put(types[i][1], types[i][0]);
            
            String arrayType = "_" + types[i][0];
            _pgNameToJavaClass.put(arrayType, "java.sql.Array");
        }
    }

    public synchronized void addDataType(String type, Class klass) throws SQLException
    {
        if (!PGobject.class.isAssignableFrom(klass))
            throw new PSQLException(GT.tr("The class {0} does not implement org.postgresql.util.PGobject.", klass.toString()), PSQLState.INVALID_PARAMETER_TYPE);

        _pgNameToPgObject.put(type, klass);
        _pgNameToJavaClass.put(type, klass.getName());
    }

    public static Iterator getPGTypeNamesWithSQLTypes()
    {
        // the map is unmodfiable
        return _pgNameToSQLType.keySet().iterator();
    }

    // no need to synchronize because getSQLType uses an unmodfiable map
    public int getSQLType(int oid) throws SQLException
    {
        return getSQLType(getPGType(oid));
    }

    public static int getSQLType(String pgTypeName)
    {
        // map is unmodifiable, no need to synchronize
        Integer i = (Integer)_pgNameToSQLType.get(pgTypeName);
        if (i != null)
            return i.intValue();
        return Types.OTHER;
    }

    public synchronized int getPGType(String pgTypeName) throws SQLException
    {
        Integer oid = (Integer)_pgNameToOid.get(pgTypeName);
        if (oid != null)
            return oid.intValue();

        String sql;
        if (_conn.haveMinimumServerVersion("7.3")) {
            sql = "SELECT oid FROM pg_catalog.pg_type WHERE typname = ?";
        } else {
            sql = "SELECT oid FROM pg_type WHERE typname = ?";
        }
        if (_getOidStatement == null)
            _getOidStatement  = _conn.prepareStatement(sql);

        _getOidStatement.setString(1, pgTypeName);

        // Go through BaseStatement to avoid transaction start.
        if (!((BaseStatement)_getOidStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN))
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        oid = new Integer(Oid.UNSPECIFIED);
        ResultSet rs = _getOidStatement.getResultSet();
        if (rs.next()) {
            oid = new Integer(rs.getInt(1));
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

        String sql;
        if (_conn.haveMinimumServerVersion("7.3")) {
            sql = "SELECT typname FROM pg_catalog.pg_type WHERE oid = ?";
        } else {
            sql = "SELECT typname FROM pg_type WHERE oid = ?";
        }
        if (_getNameStatement == null)
            _getNameStatement = _conn.prepareStatement(sql);

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

    public synchronized Class getPGobject(String type)
    {
        return (Class)_pgNameToPgObject.get(type);
    }

    public synchronized String getJavaClass(int oid) throws SQLException
    {
        String pgTypeName = getPGType(oid);
        return (String)_pgNameToJavaClass.get(pgTypeName);
    }

    public static int getPrecision(int oid, int typmod) {
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
                    return 0;
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
                    return 0;
                return typmod;

            case Oid.TEXT:
            case Oid.BYTEA:
            default:
                return 0;
        }
    }

    public static int getScale(int oid, int typmod) {
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

    public static boolean isCaseSensitive(int oid) {
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

    public static boolean isSigned(int oid) {
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

    public static int getDisplaySize(int oid, int typmod) {
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
                // varies based up the extra_float_digits GUC.
                return 14; // sign + 8 digits + decimal point + e + sign + 2 digits
            case Oid.FLOAT8:
                return 24; // sign + 17 digits + decimal point + e + sign + 3 digits
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
                    return Integer.MAX_VALUE;
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
                    return Integer.MAX_VALUE;
                return typmod;
            case Oid.TEXT:
            case Oid.BYTEA:
                return Integer.MAX_VALUE;
            default:
                return Integer.MAX_VALUE;
        }
    }

    public static int getMaximumPrecision(int oid) {
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

}
