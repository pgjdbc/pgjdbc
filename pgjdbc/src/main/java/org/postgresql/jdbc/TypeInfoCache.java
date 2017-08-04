/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Oid;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class TypeInfoCache implements TypeInfo {

  // pgname (String) -> java.sql.Types (Integer)
  private Map<String, Integer> _pgNameToSQLType;
  // oid -> java.sql.Types
  private Map<Integer, Integer> _oidToSQLType;

  // pgname (String) -> java class name (String)
  // ie "text" -> "java.lang.String"
  private Map<String, String> _pgNameToJavaClass;
  // oid -> Java class name (e.g., "java.lang.String")
  private Map<Integer, String> _oidToJavaClass;

  // oid (Integer) -> pgname (String)
  private Map<Integer, String> _oidToPgName;
  // pgname (String) -> oid (Integer)
  private Map<String, Integer> _pgNameToOid;

  // pgname (String) -> extension pgobject (Class)
  private Map<String, Class<? extends PGobject>> _pgNameToPgObject;

  // type array oid -> base type's oid
  private Map<Integer, Integer> _pgArrayToPgType;

  // array type oid -> base type array element delimiter
  private Map<Integer, Character> _arrayOidToDelimiter;

  private BaseConnection _conn;
  private final int _unknownLength;
  private PreparedStatement _getOidStatementSimple;
  private PreparedStatement _getOidStatementComplexNonArray;
  private PreparedStatement _getOidStatementComplexArray;
  private PreparedStatement _getNameStatement;

  // basic pg types info:
  // 0 - type name
  // 1 - type oid
  // 2 - sql type
  // 3 - java class
  // 4 - array type oid
  private static final Object[][] types = {
      {"int2", Oid.INT2, Types.SMALLINT, "java.lang.Integer", Oid.INT2_ARRAY},
      {"int4", Oid.INT4, Types.INTEGER, "java.lang.Integer", Oid.INT4_ARRAY},
      {"oid", Oid.OID, Types.BIGINT, "java.lang.Long", Oid.OID_ARRAY},
      {"int8", Oid.INT8, Types.BIGINT, "java.lang.Long", Oid.INT8_ARRAY},
      {"money", Oid.MONEY, Types.DOUBLE, "java.lang.Double", Oid.MONEY_ARRAY},
      {"numeric", Oid.NUMERIC, Types.NUMERIC, "java.math.BigDecimal", Oid.NUMERIC_ARRAY},
      {"float4", Oid.FLOAT4, Types.REAL, "java.lang.Float", Oid.FLOAT4_ARRAY},
      {"float8", Oid.FLOAT8, Types.DOUBLE, "java.lang.Double", Oid.FLOAT8_ARRAY},
      {"char", Oid.CHAR, Types.CHAR, "java.lang.String", Oid.CHAR_ARRAY},
      {"bpchar", Oid.BPCHAR, Types.CHAR, "java.lang.String", Oid.BPCHAR_ARRAY},
      {"varchar", Oid.VARCHAR, Types.VARCHAR, "java.lang.String", Oid.VARCHAR_ARRAY},
      {"text", Oid.TEXT, Types.VARCHAR, "java.lang.String", Oid.TEXT_ARRAY},
      {"name", Oid.NAME, Types.VARCHAR, "java.lang.String", Oid.NAME_ARRAY},
      {"bytea", Oid.BYTEA, Types.BINARY, "[B", Oid.BYTEA_ARRAY},
      {"bool", Oid.BOOL, Types.BIT, "java.lang.Boolean", Oid.BOOL_ARRAY},
      {"bit", Oid.BIT, Types.BIT, "java.lang.Boolean", Oid.BIT_ARRAY},
      {"date", Oid.DATE, Types.DATE, "java.sql.Date", Oid.DATE_ARRAY},
      {"time", Oid.TIME, Types.TIME, "java.sql.Time", Oid.TIME_ARRAY},
      {"timetz", Oid.TIMETZ, Types.TIME, "java.sql.Time", Oid.TIMETZ_ARRAY},
      {"timestamp", Oid.TIMESTAMP, Types.TIMESTAMP, "java.sql.Timestamp", Oid.TIMESTAMP_ARRAY},
      {"timestamptz", Oid.TIMESTAMPTZ, Types.TIMESTAMP, "java.sql.Timestamp",
          Oid.TIMESTAMPTZ_ARRAY},
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
      {"refcursor", Oid.REF_CURSOR, Types.REF_CURSOR, "java.sql.ResultSet", Oid.REF_CURSOR_ARRAY},
      //#endif
      {"json", Oid.JSON, Types.OTHER, "org.postgresql.util.PGobject", Oid.JSON_ARRAY},
      {"point", Oid.POINT, Types.OTHER, "org.postgresql.geometric.PGpoint", Oid.POINT_ARRAY}
  };

  /**
   * PG maps several alias to real type names. When we do queries against pg_catalog, we must use
   * the real type, not an alias, so use this mapping.
   */
  private static final HashMap<String, String> typeAliases;

  static {
    typeAliases = new HashMap<String, String>();
    typeAliases.put("smallint", "int2");
    typeAliases.put("integer", "int4");
    typeAliases.put("int", "int4");
    typeAliases.put("bigint", "int8");
    typeAliases.put("float", "float8");
    typeAliases.put("boolean", "bool");
    typeAliases.put("decimal", "numeric");
  }

  private static final String ARRAY_SUFFIX = "[]";

  public TypeInfoCache(BaseConnection conn, int unknownLength) {
    _conn = conn;
    _unknownLength = unknownLength;
    _oidToPgName = new HashMap<Integer, String>();
    _pgNameToOid = new HashMap<String, Integer>();
    _pgNameToJavaClass = new HashMap<String, String>();
    _oidToJavaClass = new HashMap<Integer, String>();
    _pgNameToPgObject = new HashMap<String, Class<? extends PGobject>>();
    _pgArrayToPgType = new HashMap<Integer, Integer>();
    _arrayOidToDelimiter = new HashMap<Integer, Character>();

    // needs to be synchronized because the iterator is returned
    // from getPGTypeNamesWithSQLTypes()
    _pgNameToSQLType = Collections.synchronizedMap(new HashMap<String, Integer>());
    _oidToSQLType = new HashMap<Integer, Integer>();

    for (Object[] type : types) {
      String pgTypeName = (String) type[0];
      Integer oid = (Integer) type[1];
      Integer sqlType = (Integer) type[2];
      String javaClass = (String) type[3];
      Integer arrayOid = (Integer) type[4];

      addCoreType(pgTypeName, oid, sqlType, javaClass, arrayOid);
    }

    _pgNameToJavaClass.put("hstore", Map.class.getName());
  }

  public synchronized void addCoreType(String pgTypeName, Integer oid, Integer sqlType,
      String javaClass, Integer arrayOid) {
    _pgNameToJavaClass.put(pgTypeName, javaClass);
    _oidToJavaClass.put(oid, javaClass);
    _pgNameToOid.put(pgTypeName, oid);
    _oidToPgName.put(oid, pgTypeName);
    _pgArrayToPgType.put(arrayOid, oid);
    _pgNameToSQLType.put(pgTypeName, sqlType);
    _oidToSQLType.put(oid, sqlType);

    // Currently we hardcode all core types array delimiter
    // to a comma. In a stock install the only exception is
    // the box datatype and it's not a JDBC core type.
    //
    Character delim = ',';
    _arrayOidToDelimiter.put(arrayOid, delim);

    String pgArrayTypeName = pgTypeName + ARRAY_SUFFIX;
    _pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
    _oidToJavaClass.put(arrayOid, "java.sql.Array");
    _pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
    _oidToSQLType.put(arrayOid, Types.ARRAY);
    _pgNameToOid.put(pgArrayTypeName, arrayOid);
    pgArrayTypeName = "_" + pgTypeName;
    if (!_pgNameToJavaClass.containsKey(pgArrayTypeName)) {
      _pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
      _pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
      _pgNameToOid.put(pgArrayTypeName, arrayOid);
      _oidToPgName.put(arrayOid, pgArrayTypeName);
    }
  }


  public synchronized void addDataType(String type, Class<? extends PGobject> klass)
      throws SQLException {
    _pgNameToPgObject.put(type, klass);
    _pgNameToJavaClass.put(type, klass.getName());
  }

  public Iterator<String> getPGTypeNamesWithSQLTypes() {
    return _pgNameToSQLType.keySet().iterator();
  }

  static class PgType {
    private final String nspname;
    private final String typname;
    private final int oid;
    private final boolean onPath;

    static PgType createElement(String nspname, boolean onPath,
        int elementOid, String elementTypname, Character elementTyptype,
        Character elementTypdelim, int arrayOid, String arrayTypname) {
      return new PgType(nspname, onPath, elementOid, elementTypname, elementTyptype,
          elementTypdelim, arrayOid,
          arrayTypname, true);
    }

    static PgType createArray(String nspname, boolean onPath,
        int elementOid, String elementTypname,
        Character elementTyptype,
        Character elementTypdelim, int arrayOid, String arrayTypname) {
      return new PgType(nspname, onPath, elementOid, elementTypname, elementTyptype,
          elementTypdelim, arrayOid,
          arrayTypname, false);
    }

    private static HashMap<Character, Integer> typtypeToSqlType =
        new HashMap<Character, Integer>() {
          {
            put('c', Types.STRUCT);
            put('d', Types.DISTINCT);
            put('e', Types.VARCHAR);
          }
        };

    /*
    This is quite naive and doesn't take into account the special handing of core types that are
    loaded on instantiation. cachePgType checks to see if the relevant HashMaps are already populated
    before writing to prevent accidentally overwriting these values.
     */
    private static int sqlType(boolean isArray, Character typtype) {
      if (isArray) {
        return Types.ARRAY;
      }

      Integer sqlType = typtypeToSqlType.get(typtype);

      if (sqlType == null) {
        return Types.OTHER;
      }

      return sqlType;
    }

    private final boolean isElement;
    private final int elementOid;
    @SuppressWarnings("unused")
    private final String elementTypname;
    private final int arrayOid;
    @SuppressWarnings("unused")
    private final String arrayTypname;
    private final int sqlType;
    @SuppressWarnings("unused")
    private final Character typdelim;

    private PgType(String nspname, boolean onPath, int elementOid, String elementTypname,
        Character elementTyptype, Character typdelim, int arrayOid, String arrayTypname,
        boolean isElement) {
      this.nspname = nspname;
      this.onPath = onPath;
      this.elementOid = elementOid;
      this.elementTypname = elementTypname;
      this.typdelim = typdelim;
      this.arrayOid = arrayOid;
      this.arrayTypname = arrayTypname;
      this.isElement = isElement;
      this.sqlType = sqlType(!isElement, elementTyptype);
      this.typname = isElement ? elementTypname : arrayTypname;
      this.oid = isElement ? elementOid : arrayOid;
    }

    int oid() {
      return oid;
    }

    int elementOid() {
      return elementOid;
    }

    int arrayOid() {
      return arrayOid;
    }

    boolean isElement() {
      return isElement;
    }

    Character delimiter() {
      return typdelim;
    }

    int sqlType() {
      return sqlType;
    }

    boolean onPath() {
      return onPath;
    }

    private static String quote(String ident) {
      boolean hasDot = ident.indexOf('.') != -1;
      boolean isQuoted = ident.startsWith("\"") && ident.endsWith("\"");
      boolean isCaseSensitive = !ident.equals(ident.toLowerCase());
      boolean hasArraySuffix = ident.endsWith(ARRAY_SUFFIX);
      return (hasDot || isQuoted || isCaseSensitive || hasArraySuffix) ? '"' + ident + '"' : ident;
    }

    String qualifiedName() {
      return "\"" + nspname + "\".\"" + typname + "\"";
    }

    String onPathName() {
      return quote(typname);
    }

    String cacheName() {
      return onPath ? onPathName() : qualifiedName();
    }
  }

  private synchronized void cachePgType(PgType pgType) {
    int oid = pgType.oid();
    int elementOid = pgType.elementOid();
    int arrayOid = pgType.arrayOid();

    _pgArrayToPgType.put(arrayOid, elementOid);
    _arrayOidToDelimiter.put(arrayOid, pgType.delimiter());

    String cachedName = pgType.qualifiedName();
    _pgNameToOid.put(cachedName, oid);

    int sqlType = pgType.sqlType();
    // Take care not to overwrite core type entries loaded on instantiation.
    if (!_oidToSQLType.containsKey(elementOid)) {
      _oidToSQLType.put(elementOid, pgType.sqlType);
    }

    if (!_oidToSQLType.containsKey(arrayOid)) {
      _oidToSQLType.put(arrayOid, Types.ARRAY);
    }

    if (!_pgNameToSQLType.containsKey(cachedName)) {
      _pgNameToSQLType.put(cachedName, sqlType);
    }

    if (pgType.isElement) {
      _oidToJavaClass.put(arrayOid, "java.sql.Array");
    } else {
      _oidToJavaClass.put(oid, "java.sql.Array");
      if (!_pgNameToJavaClass.containsKey(cachedName)) {
        _pgNameToJavaClass.put(cachedName, "java.sql.Array");
      }
    }

    if (pgType.onPath()) {
      cachedName = pgType.onPathName();
      _pgNameToOid.put(cachedName, oid);

      // Take care not to overwrite core type entries loaded on instantiation.
      if (!_pgNameToSQLType.containsKey(cachedName)) {
        _pgNameToSQLType.put(cachedName, sqlType);
      }

      if (!pgType.isElement() && !_pgNameToJavaClass.containsKey(cachedName)) {
        _pgNameToJavaClass.put(cachedName, "java.sql.Array");
      }
    }

    _oidToPgName.put(oid, cachedName);
  }

  private synchronized void cachePgType(PgType pgType, String nameString) {
    cachePgType(pgType);

    if (nameString.equals(pgType.qualifiedName())
        || (pgType.onPath() && nameString.equals(pgType.onPathName()))) {
      // Nothing new to cache.
      return;
    }

    _pgNameToOid.put(nameString, pgType.oid());
    _pgNameToSQLType.put(nameString, pgType.sqlType());
    if (!pgType.isElement()) {
      _pgNameToJavaClass.put(nameString, "java.sql.Array");
    }
  }

  static class ParsedTypeName {
    private final String nspname;
    private final String typname;
    private final boolean isArray;
    private final boolean isSimple;

    static ParsedTypeName fromString(String pgTypeName) {
      boolean isArray = pgTypeName.endsWith(ARRAY_SUFFIX);
      boolean hasQuote = pgTypeName.contains("\"");
      int dotIndex = pgTypeName.indexOf('.');

      if (dotIndex == -1 && !hasQuote && !isArray) {
        return new ParsedTypeName(pgTypeName);
      }

      String fullName = isArray ? pgTypeName.substring(0, pgTypeName.length() - 2) : pgTypeName;
      String schema;
      String name;

      // simple use case
      if (dotIndex == -1) {
        schema = null;
        name = fullName;
      } else {
        if (fullName.startsWith("\"")) {
          if (fullName.endsWith("\"")) {
            if (fullName.length() == 3) {
              // type name string is dot-quote-dot (".")
              schema = null;
              name = fullName;
            } else {
              String[] parts = fullName.split("\"\\.\"");
              schema = parts.length == 2 ? parts[0] + "\"" : null;
              name = parts.length == 2 ? "\"" + parts[1] : parts[0];
            }
          } else {
            int lastDotIndex = fullName.lastIndexOf('.');
            name = fullName.substring(lastDotIndex + 1);
            schema = fullName.substring(0, lastDotIndex);
          }
        } else {
          schema = fullName.substring(0, dotIndex);
          name = fullName.substring(dotIndex + 1);
        }
      }

      if (schema != null && schema.startsWith("\"") && schema.endsWith("\"")) {
        int schemaLength = schema.length();
        schema = (schemaLength == 1) ? schema : schema.substring(1, schema.length() - 1);
      } else if (schema != null) {
        schema = schema.toLowerCase();
      }

      if (name.startsWith("\"") && name.endsWith("\"")) {
        int nameLength = name.length();
        name = (nameLength == 1) ? name : name.substring(1, name.length() - 1);
      } else {
        name = name.toLowerCase();
      }

      return new ParsedTypeName(schema, name, isArray);
    }

    private ParsedTypeName(String typname) {
      this.nspname = null;
      this.typname = typname;
      this.isArray = false;
      this.isSimple = true;
    }

    private ParsedTypeName(String nspname, String typname, boolean isArray) {
      this.nspname = nspname;
      this.typname = typname;
      this.isArray = isArray;
      this.isSimple = false;
    }

    public String nspname() {
      return nspname;
    }

    public String typname() {
      return typname;
    }

    boolean isArray() {
      return isArray;
    }

    boolean isSimple() {
      return isSimple;
    }
  }

  public int getSQLType(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return Types.OTHER;
    }

    Integer sqlType = _oidToSQLType.get(oid);
    if (sqlType != null) {
      return sqlType;
    }

    PgType pgType = fetchPgType(oid);
    if (pgType == null) {
      return Types.OTHER;
    }

    return pgType.sqlType();
  }

  public synchronized int getSQLType(String pgTypeName) throws SQLException {
    if (pgTypeName == null) {
      return Types.OTHER;
    }

    Integer i = _pgNameToSQLType.get(pgTypeName);
    if (i != null) {
      return i;
    }

    /*
    This is a quick and dirty check based on the name only. We want to pass the type name string
    (pgTypeName) to fetchPgType so that the type name string is properly cached.

    We may not want to do this check anyway given the type may not exist, in which case we want to
    return java.sql.Types.OTHER. However, this is what the current behavior is.
     */
    ParsedTypeName typeName = ParsedTypeName.fromString(pgTypeName);
    if (typeName.isArray()) {
      return Types.ARRAY;
    }

    PgType pgType = fetchPgType(pgTypeName);

    if (pgType == null) {
      return Types.OTHER;
    }

    return pgType.sqlType();
  }

  enum PgTypeResultSetColumn {
    OID(1), ON_PATH(2), NSPNAME(3), IS_ARRAY(4),
    TYPNAME(5), TYPELEM(6), TYPTYPE(7), TYPDELIM(8),
    ELEMENT_TYPNAME(9), ELEMENT_TYPTYPE(10), ELEMENT_TYPDELIM(11),
    ARRAY_OID(12), ARRAY_TYPNAME(13);
    final int idx;

    PgTypeResultSetColumn(int idx) {
      this.idx = idx;
    }
  }

  private PreparedStatement getOidStatement(ParsedTypeName typeName) throws SQLException {
    if (typeName.isSimple()) {
      if (_getOidStatementSimple == null) {
        // see comments in @getSQLType()
        String sql = "SELECT t.oid, n.nspname = ANY(current_schemas(true)), n.nspname,"
            + " t.typinput = 'array_in'::regproc,"
            + " t.typname, t.typelem, t.typtype, t.typdelim,"
            + " e.typname, e.typtype, e.typdelim,"
            + " arr.oid, e.typtype"
            + "  FROM pg_catalog.pg_type t"
            + "  JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace"
            + "  LEFT JOIN pg_catalog.pg_type arr ON (arr.typelem, arr.typinput) = (t.oid, 'array_in'::regproc)"
            + "  LEFT JOIN pg_catalog.pg_type e ON t.typelem = e.oid"
            + "  LEFT JOIN (SELECT s.r, (current_schemas(false))[r] AS nspname"
            + "               FROM generate_series(1, array_upper(current_schemas(false), 1)) AS s (r)) AS sp"
            + "    USING (nspname)"
            + " WHERE t.typname = ?"
            + " ORDER BY sp.r, t.oid DESC LIMIT 1;";
        _getOidStatementSimple = _conn.prepareStatement(sql);
      }
      // coerce to lower case to handle upper case type names
      String lcName = typeName.typname().toLowerCase();
      _getOidStatementSimple.setString(1, lcName);
      return _getOidStatementSimple;
    }

    PreparedStatement oidStatementComplex;
    if (_getOidStatementComplexNonArray == null) {
      String sql = "SELECT t.oid, n.nspname = ANY(current_schemas(true)), n.nspname,"
          + " t.typinput = 'array_in'::regproc,"
          + " t.typname, t.typelem, t.typtype, t.typdelim,"
          + " e.typname, e.typtype, e.typdelim,"
          + " arr.oid, e.typtype"
          + "  FROM pg_catalog.pg_type t"
          + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
          + "  LEFT JOIN (SELECT s.r, (current_schemas(true))[r] AS nspname"
          + "               FROM generate_series(1, array_upper(current_schemas(true), 1)) AS s (r)) AS sp"
          + "    USING (nspname)"
          + "  LEFT JOIN pg_catalog.pg_type arr ON (arr.typelem, arr.typinput) = (t.oid, 'array_in'::regproc)"
          + "  LEFT JOIN pg_catalog.pg_type e ON t.typelem = e.oid"
          + " WHERE t.typname = ? AND (n.nspname = ? OR ? IS NULL AND n.nspname = ANY (current_schemas(true)))"
          + " ORDER BY sp.r LIMIT 1";
      _getOidStatementComplexNonArray = _conn.prepareStatement(sql);
    }
    oidStatementComplex = _getOidStatementComplexNonArray;
    oidStatementComplex.setString(1, typeName.typname());
    oidStatementComplex.setString(2, typeName.nspname());
    oidStatementComplex.setString(3, typeName.nspname());
    return oidStatementComplex;
  }

  private PreparedStatement getArrayOidStatement(ParsedTypeName typeName) throws SQLException {
    PreparedStatement oidStatementComplex;
    if (_getOidStatementComplexArray == null) {
      String sql;
      if (_conn.haveMinimumServerVersion(ServerVersion.v8_3)) {
        sql = "SELECT arr.oid, n.nspname = ANY(current_schemas(true)), n.nspname,"
            + " TRUE, arr.typname, arr.typelem, arr.typtype, arr.typdelim,"
            + " e.typname, e.typtype, e.typdelim, arr.oid, arr.typname"
            + "  FROM pg_catalog.pg_type e"
            + "  JOIN pg_catalog.pg_namespace n ON e.typnamespace = n.oid"
            + "  JOIN pg_catalog.pg_type arr ON arr.oid = e.typarray"
            + "  LEFT JOIN (SELECT s.r, (current_schemas(true))[r] AS nspname"
            + "               FROM generate_series(1, array_upper(current_schemas(true), 1)) AS s (r)) AS sp"
            + "    USING (nspname)"
            + " WHERE e.typname = ? AND (n.nspname = ? OR ? IS NULL AND n.nspname = ANY (current_schemas(true)))"
            + " ORDER BY sp.r LIMIT 1";
      } else {
        sql = "SELECT arr.oid, n.nspname = ANY(current_schemas(true)), n.nspname,"
            + " TRUE, arr.typname, arr.typelem, arr.typtype, arr.typdelim,"
            + " e.typname, e.typtype, e.typdelim, arr.oid, arr.typname"
            + "  FROM pg_catalog.pg_type e"
            + "  JOIN pg_catalog.pg_namespace n ON e.typnamespace = n.oid"
            + "  JOIN pg_catalog.pg_type arr ON (arr.typelem, arr.typinput) = (e.oid, 'array_in'::regproc)"
            + "  LEFT JOIN (SELECT s.r, (current_schemas(true))[r] AS nspname"
            + "               FROM generate_series(1, array_upper(current_schemas(true), 1)) AS s (r)) AS sp"
            + "    USING (nspname)"
            + " WHERE e.typname = ? AND (n.nspname = ? OR ? IS NULL AND n.nspname = ANY (current_schemas(true)))"
            + " ORDER BY sp.r LIMIT 1";
      }
      _getOidStatementComplexArray = _conn.prepareStatement(sql);
    }
    oidStatementComplex = _getOidStatementComplexArray;
    oidStatementComplex.setString(1, typeName.typname());
    oidStatementComplex.setString(2, typeName.nspname());
    oidStatementComplex.setString(3, typeName.nspname());
    return oidStatementComplex;
  }

  private synchronized PgType fetchPgType(String pgTypeName) throws SQLException {
    ParsedTypeName typeName = ParsedTypeName.fromString(pgTypeName);
    PreparedStatement oidStatement = typeName.isArray()
        ? getArrayOidStatement(typeName) : getOidStatement(typeName);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) oidStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    PgType pgType = null;
    ResultSet rs = oidStatement.getResultSet();
    if (rs.next()) {
      boolean onPath = rs.getBoolean(PgTypeResultSetColumn.ON_PATH.idx);
      String schema = rs.getString(PgTypeResultSetColumn.NSPNAME.idx);
      boolean isArray = rs.getBoolean(PgTypeResultSetColumn.IS_ARRAY.idx);
      pgType = isArray
          ? PgType.createArray(schema, onPath,
          (int) rs.getLong(PgTypeResultSetColumn.TYPELEM.idx),
          rs.getString(PgTypeResultSetColumn.ELEMENT_TYPNAME.idx),
          rs.getString(PgTypeResultSetColumn.ELEMENT_TYPTYPE.idx).charAt(0),
          rs.getString(PgTypeResultSetColumn.ELEMENT_TYPDELIM.idx).charAt(0),
          (int) rs.getLong(PgTypeResultSetColumn.OID.idx),
          rs.getString(PgTypeResultSetColumn.TYPNAME.idx))
          : PgType.createElement(schema, onPath,
              (int) rs.getLong(PgTypeResultSetColumn.OID.idx),
              rs.getString(PgTypeResultSetColumn.TYPNAME.idx),
              rs.getString(PgTypeResultSetColumn.TYPTYPE.idx).charAt(0),
              rs.getString(PgTypeResultSetColumn.TYPDELIM.idx).charAt(0),
              (int) rs.getLong(PgTypeResultSetColumn.ARRAY_OID.idx),
              rs.getString(PgTypeResultSetColumn.ARRAY_TYPNAME.idx));
    }
    rs.close();

    if (pgType == null) {
      return null;
    }

    cachePgType(pgType, pgTypeName);
    return pgType;
  }

  public synchronized int getPGType(String pgTypeName) throws SQLException {
    if (pgTypeName == null) {
      return Oid.UNSPECIFIED;
    }

    Integer oid = _pgNameToOid.get(pgTypeName);
    if (oid != null) {
      return oid;
    }

    PgType pgType = fetchPgType(pgTypeName);

    if (pgType == null) {
      return Oid.UNSPECIFIED;
    }

    return pgType.oid();
  }

  private synchronized PgType fetchPgType(int oid) throws SQLException {
    if (_getNameStatement == null) {
      String sql = "SELECT t.oid, n.nspname = ANY(current_schemas(true)), n.nspname,"
          + " t.typinput = 'array_in'::regproc, t.typname, t.typelem, t.typtype, t.typdelim,"
          + " e.typname, e.typtype, e.typdelim, arr.oid, arr.typname"
          + " FROM pg_catalog.pg_type t"
          + " JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
          + " LEFT JOIN pg_catalog.pg_type e ON t.typelem = e.oid"
          + " LEFT JOIN pg_catalog.pg_type arr ON (t.oid, 'array_in'::regproc) = (arr.typelem, arr.typinput)"
          + " WHERE t.oid = ?";

      _getNameStatement = _conn.prepareStatement(sql);
    }
    _getNameStatement.setInt(1, oid);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) _getNameStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    PgType pgType = null;
    ResultSet rs = _getNameStatement.getResultSet();
    if (rs.next()) {
      boolean onPath = rs.getBoolean(PgTypeResultSetColumn.ON_PATH.idx);
      String schema = rs.getString(PgTypeResultSetColumn.NSPNAME.idx);
      boolean isArray = rs.getBoolean(PgTypeResultSetColumn.IS_ARRAY.idx);
      pgType = isArray
          ? PgType.createArray(schema, onPath,
          (int) rs.getLong(PgTypeResultSetColumn.TYPELEM.idx),
          rs.getString(PgTypeResultSetColumn.ELEMENT_TYPNAME.idx),
          rs.getString(PgTypeResultSetColumn.ELEMENT_TYPTYPE.idx).charAt(0),
          rs.getString(PgTypeResultSetColumn.ELEMENT_TYPDELIM.idx).charAt(0),
          oid, rs.getString(PgTypeResultSetColumn.TYPNAME.idx))
          : PgType.createElement(schema, onPath,
              oid,
              rs.getString(PgTypeResultSetColumn.TYPNAME.idx),
              rs.getString(PgTypeResultSetColumn.TYPTYPE.idx).charAt(0),
              rs.getString(PgTypeResultSetColumn.TYPDELIM.idx).charAt(0),
              (int) rs.getLong(PgTypeResultSetColumn.ARRAY_OID.idx),
              rs.getString(PgTypeResultSetColumn.ARRAY_TYPNAME.idx));
    }
    rs.close();

    if (pgType == null) {
      return null;
    }

    cachePgType(pgType);
    return pgType;
  }

  public synchronized String getPGType(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return null;
    }

    String pgTypeName = _oidToPgName.get(oid);
    if (pgTypeName != null) {
      return pgTypeName;
    }

    PgType pgType = fetchPgType(oid);
    if (pgType == null) {
      return null;
    }

    return pgType.cacheName();
  }

  public int getPGArrayType(String elementTypeName) throws SQLException {
    if (elementTypeName == null) {
      return Oid.UNSPECIFIED;
    }

    /*
    We can't replace this type name munging without breaking legacy behavior because
    ParsedTypeName.fromString handles simple names differently from those with array suffixes.
    "foo[]" is not the same as "the array type for base type foo"
     */
    String canonicalTypeName = getTypeForAlias(elementTypeName);
    return getPGType(canonicalTypeName + ARRAY_SUFFIX);
  }

  /**
   * Return the oid of the array's base element if it's an array, if not return the provided oid.
   * This doesn't do any database lookups, so it's only useful for the originally provided type
   * mappings. This is fine for it's intended uses where we only have intimate knowledge of types
   * that are already known to the driver.
   *
   * @param oid input oid
   * @return oid of the array's base element or the provided oid (if not array)
   */
  protected synchronized int convertArrayToBaseOid(int oid) {
    Integer i = _pgArrayToPgType.get(oid);
    if (i == null) {
      return oid;
    }
    return i;
  }

  public synchronized char getArrayDelimiter(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return ',';
    }

    Character delim = _arrayOidToDelimiter.get(oid);
    if (delim != null) {
      return delim;
    }

    PgType pgType = fetchPgType(oid);

    if (pgType == null) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    if (pgType.isElement()) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    return pgType.delimiter();
  }

  public synchronized int getPGArrayElement(int arrayOid) throws SQLException {
    if (arrayOid == Oid.UNSPECIFIED) {
      return Oid.UNSPECIFIED;
    }

    Integer oid = _pgArrayToPgType.get(arrayOid);

    if (oid != null) {
      return oid;
    }

    PgType pgType = fetchPgType(arrayOid);

    if (pgType == null) {
      return Oid.UNSPECIFIED;
    }

    if (pgType.isElement()) {
      // arrayOid is actually the oid of an element type.
      return Oid.UNSPECIFIED;
    }

    cachePgType(pgType);
    return pgType.elementOid();
  }

  public synchronized Class<? extends PGobject> getPGobject(String type) {
    return _pgNameToPgObject.get(type);
  }

  public synchronized String getJavaClass(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return null;
    }

    String javaClass = _oidToJavaClass.get(oid);
    if (javaClass != null) {
      return javaClass;
    }

    String pgTypeName = getPGType(oid);

    String result = _pgNameToJavaClass.get(pgTypeName);
    if (result != null) {
      return result;
    }

    if (getSQLType(pgTypeName) == Types.ARRAY) {
      result = "java.sql.Array";
      _pgNameToJavaClass.put(pgTypeName, result);
      _oidToJavaClass.put(oid, result);
    }

    return result;
  }

  public String getTypeForAlias(String alias) {
    if (alias == null) {
      return null;
    }

    String type = typeAliases.get(alias);
    if (type != null) {
      return type;
    }
    if (alias.indexOf('"') == -1) {
      type = typeAliases.get(alias.toLowerCase());
      if (type != null) {
        return type;
      }
    }
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
        if (typmod == -1) {
          return 0;
        }
        return ((typmod - 4) & 0xFFFF0000) >> 16;

      case Oid.CHAR:
      case Oid.BOOL:
        return 1;

      case Oid.BPCHAR:
      case Oid.VARCHAR:
        if (typmod == -1) {
          return _unknownLength;
        }
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
        if (typmod == -1) {
          return _unknownLength;
        }
        return typmod;

      case Oid.TEXT:
      case Oid.BYTEA:
      default:
        return _unknownLength;
    }
  }

  public int getScale(int oid, int typmod) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
      case Oid.FLOAT4:
        return 8;
      case Oid.FLOAT8:
        return 17;
      case Oid.NUMERIC:
        if (typmod == -1) {
          return 0;
        }
        return (typmod - 4) & 0xFFFF;
      case Oid.TIME:
      case Oid.TIMETZ:
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        if (typmod == -1) {
          return 6;
        }
        return typmod;
      case Oid.INTERVAL:
        if (typmod == -1) {
          return 6;
        }
        return typmod & 0xFFFF;
      default:
        return 0;
    }
  }

  public boolean isCaseSensitive(int oid) {
    oid = convertArrayToBaseOid(oid);
    switch (oid) {
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
    switch (oid) {
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
    switch (oid) {
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
        return 13; // "4713-01-01 BC" to "01/01/4713 BC" - "31/12/32767"
      case Oid.TIME:
      case Oid.TIMETZ:
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        // Calculate the number of decimal digits + the decimal point.
        int secondSize;
        switch (typmod) {
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

        switch (oid) {
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
        // SELECT LENGTH('-123456789 years 11 months 33 days 23 hours 10.123456 seconds'::interval);
        return 49;
      case Oid.VARCHAR:
      case Oid.BPCHAR:
        if (typmod == -1) {
          return _unknownLength;
        }
        return typmod - 4;
      case Oid.NUMERIC:
        if (typmod == -1) {
          return 131089; // SELECT LENGTH(pow(10::numeric,131071)); 131071 = 2^17-1
        }
        int precision = (typmod - 4 >> 16) & 0xffff;
        int scale = (typmod - 4) & 0xffff;
        // sign + digits + decimal point (only if we have nonzero scale)
        return 1 + precision + (scale != 0 ? 1 : 0);
      case Oid.BIT:
        return typmod;
      case Oid.VARBIT:
        if (typmod == -1) {
          return _unknownLength;
        }
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
    switch (oid) {
      case Oid.NUMERIC:
        return 1000;
      case Oid.TIME:
      case Oid.TIMETZ:
        // Technically this depends on the --enable-integer-datetimes
        // configure setting. It is 6 with integer and 10 with float.
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

  public boolean requiresQuoting(int oid) throws SQLException {
    int sqlType = getSQLType(oid);
    return requiresQuotingSqlType(sqlType);
  }

  /**
   * Returns true if particular sqlType requires quoting. This method is used internally by the
   * driver, so it might disappear without notice.
   *
   * @param sqlType sql type as in java.sql.Types
   * @return true if the type requires quoting
   * @throws SQLException if something goes wrong
   */
  public boolean requiresQuotingSqlType(int sqlType) throws SQLException {
    switch (sqlType) {
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
