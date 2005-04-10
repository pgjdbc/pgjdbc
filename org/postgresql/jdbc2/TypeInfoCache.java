/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2005, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *   $PostgreSQL$
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
    private static Map _pgNameToSQLType;

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

    private static Object types[][] = {
        {"int2", new Integer(Oid.INT2), new Integer(Types.SMALLINT), "java.lang.Short"},
        {"int4", new Integer(Oid.INT4), new Integer(Types.INTEGER), "java.lang.Integer"},
        {"oid", new Integer(Oid.OID), new Integer(Types.INTEGER), "java.lang.Integer"},
        {"int8", new Integer(Oid.INT8), new Integer(Types.BIGINT), "java.lang.Long"},
        {"money", new Integer(Oid.MONEY), new Integer(Types.DOUBLE), "java.lang.Double"},
        {"numeric", new Integer(Oid.NUMERIC), new Integer(Types.NUMERIC), "java.math.BigDecimal"},
        {"float4", new Integer(Oid.FLOAT4), new Integer(Types.REAL), "java.lang.Float"},
        {"float8", new Integer(Oid.FLOAT8), new Integer(Types.DOUBLE), "java.lang.Double"},
        {"bpchar", new Integer(Oid.BPCHAR), new Integer(Types.CHAR), "java.lang.String"},
        {"varchar", new Integer(Oid.VARCHAR), new Integer(Types.VARCHAR), "java.lang.String"},
        {"text", new Integer(Oid.TEXT), new Integer(Types.VARCHAR), "java.lang.String"},
        {"name", new Integer(Oid.NAME), new Integer(Types.VARCHAR), "java.lang.String"},
        {"bytea", new Integer(Oid.BYTEA), new Integer(Types.BINARY), "java.io.InputStream"},
        {"bool", new Integer(Oid.BOOL), new Integer(Types.BIT), "java.lang.Boolean"},
        {"bit", new Integer(Oid.BIT), new Integer(Types.BIT), "java.lang.Boolean"},
        {"date", new Integer(Oid.DATE), new Integer(Types.DATE), "java.sql.Date"},
        {"time", new Integer(Oid.TIME), new Integer(Types.TIME), "java.sql.Time"},
        {"timetz", new Integer(Oid.TIMETZ), new Integer(Types.TIME), "java.sql.Time"},
        {"timestamp", new Integer(Oid.TIMESTAMP), new Integer(Types.TIMESTAMP), "java.sql.Timestamp"},
        {"timestamptz", new Integer(Oid.TIMESTAMPTZ), new Integer(Types.TIMESTAMP), "java.sql.Timestamp"}
    };

    public TypeInfoCache(BaseConnection conn)
    {
        _conn = conn;
        _oidToPgName = Collections.synchronizedMap(new HashMap());
        _pgNameToOid = Collections.synchronizedMap(new HashMap());
        _pgNameToSQLType = Collections.synchronizedMap(new HashMap());
        _pgNameToJavaClass = Collections.synchronizedMap(new HashMap());
        _pgNameToPgObject = Collections.synchronizedMap(new HashMap());

        for (int i=0; i<types.length; i++) {
            _pgNameToSQLType.put(types[i][0], types[i][2]);
            _pgNameToJavaClass.put(types[i][0], types[i][3]);
            _pgNameToOid.put(types[i][0], types[i][1]);
            _oidToPgName.put(types[i][1], types[i][0]);
            
            String arrayType = "_" + types[i][0];
            _pgNameToSQLType.put(arrayType, new Integer(Types.ARRAY));
            _pgNameToJavaClass.put(arrayType, "java.sql.Array");
        }
    }

    public void addDataType(String type, Class klass) throws SQLException
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

    public int getSQLType(String pgTypeName)
    {
        Integer i = (Integer)_pgNameToSQLType.get(pgTypeName);
        if (i != null)
            return i.intValue();
        return Types.OTHER;
    }

    public int getPGType(String pgTypeName) throws SQLException
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

        oid = new Integer(Oid.INVALID);
        ResultSet rs = _getOidStatement.getResultSet();
        if (rs.next()) {
            oid = new Integer(rs.getInt(1));
            _oidToPgName.put(oid, pgTypeName);
        }
        _pgNameToOid.put(pgTypeName, oid);
        rs.close();

        return oid.intValue();
    }

    public String getPGType(int oid) throws SQLException
    {
        if (oid == Oid.INVALID)
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

    public Class getPGobject(String type)
    {
        return (Class)_pgNameToPgObject.get(type);
    }

    public String getJavaClass(int oid) throws SQLException
    {
        String pgTypeName = getPGType(oid);
        return (String)_pgNameToJavaClass.get(pgTypeName);
    }

}
