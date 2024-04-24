/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Oid;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.TypeInfo;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TypeInfoCache implements TypeInfo {

  private static final Logger LOGGER = Logger.getLogger(TypeInfoCache.class.getName());

  static final class ObjectName {
    final @Nullable String namespace;
    final String name;

    ObjectName(@Nullable String namespace, String name) {
      this.namespace = namespace;
      this.name = name;
    }

    static ObjectName parse(String pgTypeName) {
      boolean isArray = pgTypeName.endsWith("[]");
      boolean hasQuote = pgTypeName.contains("\"");
      int dotIndex = pgTypeName.indexOf('.');
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
            String[] parts = fullName.split("\"\\.\"");
            schema = parts.length == 2 ? parts[0] + "\"" : null;
            name = parts.length == 2 ? "\"" + parts[1] : parts[0];
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
        schema = schema.substring(1, schema.length() - 1);
      } else if (schema != null) {
        schema = schema.toLowerCase(Locale.ROOT);
      }
      if (name.startsWith("\"") && name.endsWith("\"")) {
        name = name.substring(1, name.length() - 1);
      } else {
        name = name.toLowerCase(Locale.ROOT);
      }
      return new ObjectName(schema, name);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ObjectName that = (ObjectName) o;
      return Objects.equals(namespace, that.namespace) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(namespace) * 31 + name.hashCode();
    }
  }

  static final class PgType {
    final ObjectName typeName;
    final String fullName;
    final int oid;
    final int sqlType;
    final @Nullable Class<?> javaClass;

    final int typelem;
    final int arrayOid;
    final char delimiter;

    PgType(ObjectName typeName, String fullName, int oid, int sqlType, @Nullable Class<?> javaClass, int typelem, int arrayOid) {
      this.typeName = typeName;
      this.fullName = fullName;
      this.oid = oid;
      this.sqlType = sqlType;
      this.typelem = typelem;
      this.javaClass = javaClass;
      this.arrayOid = arrayOid;
      // Currently, we hardcode all core types array delimiter
      // to a comma. In a stock install the only exception is
      // the box datatype, and it's not a JDBC core type.
      //
      this.delimiter = oid == Oid.BOX || oid == Oid.BOX_ARRAY ? ';' : ',';
    }
  }

  private final Map<Integer, PgType> typesByOid;
  private final Map<String, PgType> typesByPgName;

  // pgname (String) -> java.sql.Types (Integer)
  // private Map<String, Integer> pgNameToSQLType;

//   private Map<Integer, Integer> oidToSQLType;

  // pgname (String) -> java class name (String)
  // ie "text" -> "java.lang.String"

  // oid (Integer) -> pgname (String)
  // private Map<Integer, String> oidToPgName;
  // pgname (String) -> oid (Integer)
  // private Map<String, Integer> pgNameToOid;

  // pgname (String) -> extension pgobject (Class)
  private final Map<String, Class<? extends PGobject>> pgNameToPgObject;

  // array type oid -> base type array element delimiter
//   private Map<Integer, Character> arrayOidToDelimiter;

  private final BaseConnection conn;
  private final int unknownLength;
  private @Nullable PreparedStatement findPgTypeByName;
  private @Nullable PreparedStatement findPgTypeByOid;
  private @Nullable PreparedStatement findAllPgTypes;
  private final ResourceLock lock = new ResourceLock();

  // Note: this is generated with org.postgresql.jdbc.TypeInfoCacheTest.generateBaseTypes
  private static final PgType[] BASE_TYPES = {
      new PgType(new ObjectName("pg_catalog", "bit"), "bit", Oid.BIT, Types.BIT, Boolean.class, Oid.UNSPECIFIED, Oid.BIT_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_bit"), "bit[]", Oid.BIT_ARRAY, Types.ARRAY, Array.class, Oid.BIT, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "bool"), "boolean", Oid.BOOL, Types.BIT, Boolean.class, Oid.UNSPECIFIED, Oid.BOOL_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_bool"), "boolean[]", Oid.BOOL_ARRAY, Types.ARRAY, Array.class, Oid.BOOL, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "box"), "box", Oid.BOX, Types.OTHER, org.postgresql.geometric.PGbox.class, Oid.POINT, Oid.BOX_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_box"), "box[]", Oid.BOX_ARRAY, Types.ARRAY, Array.class, Oid.BOX, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "bpchar"), "character", Oid.BPCHAR, Types.CHAR, String.class, Oid.UNSPECIFIED, Oid.BPCHAR_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_bpchar"), "character[]", Oid.BPCHAR_ARRAY, Types.ARRAY, Array.class, Oid.BPCHAR, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "bytea"), "bytea", Oid.BYTEA, Types.BINARY, byte[].class, Oid.UNSPECIFIED, Oid.BYTEA_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_bytea"), "bytea[]", Oid.BYTEA_ARRAY, Types.ARRAY, Array.class, Oid.BYTEA, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "circle"), "circle", Oid.CIRCLE, Types.OTHER, org.postgresql.geometric.PGcircle.class, Oid.UNSPECIFIED, Oid.CIRCLE_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_circle"), "circle[]", Oid.CIRCLE_ARRAY, Types.ARRAY, Array.class, Oid.CIRCLE, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "date"), "date", Oid.DATE, Types.DATE, java.sql.Date.class, Oid.UNSPECIFIED, Oid.DATE_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_date"), "date[]", Oid.DATE_ARRAY, Types.ARRAY, Array.class, Oid.DATE, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "float4"), "real", Oid.FLOAT4, Types.REAL, Float.class, Oid.UNSPECIFIED, Oid.FLOAT4_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_float4"), "real[]", Oid.FLOAT4_ARRAY, Types.ARRAY, Array.class, Oid.FLOAT4, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "float8"), "double precision", Oid.FLOAT8, Types.DOUBLE, Double.class, Oid.UNSPECIFIED, Oid.FLOAT8_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_float8"), "double precision[]", Oid.FLOAT8_ARRAY, Types.ARRAY, Array.class, Oid.FLOAT8, Oid.UNSPECIFIED),
      new PgType(new ObjectName("public", "hstore"), "hstore", Oid.HSTORE, Types.OTHER, java.util.Map.class, Oid.UNSPECIFIED, Oid.HSTORE_ARRAY),
      new PgType(new ObjectName("public", "_hstore"), "hstore[]", Oid.HSTORE_ARRAY, Types.ARRAY, Array.class, Oid.HSTORE, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int2"), "smallint", Oid.INT2, Types.SMALLINT, Integer.class, Oid.UNSPECIFIED, Oid.INT2_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_int2"), "smallint[]", Oid.INT2_ARRAY, Types.ARRAY, Array.class, Oid.INT2, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int4"), "integer", Oid.INT4, Types.INTEGER, Integer.class, Oid.UNSPECIFIED, Oid.INT4_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_int4"), "integer[]", Oid.INT4_ARRAY, Types.ARRAY, Array.class, Oid.INT4, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int8"), "bigint", Oid.INT8, Types.BIGINT, Long.class, Oid.UNSPECIFIED, Oid.INT8_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_int8"), "bigint[]", Oid.INT8_ARRAY, Types.ARRAY, Array.class, Oid.INT8, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "interval"), "interval", Oid.INTERVAL, Types.OTHER, org.postgresql.util.PGInterval.class, Oid.UNSPECIFIED, Oid.INTERVAL_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_interval"), "interval[]", Oid.INTERVAL_ARRAY, Types.ARRAY, Array.class, Oid.INTERVAL, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "json"), "json", Oid.JSON, Types.OTHER, org.postgresql.util.PGobject.class, Oid.UNSPECIFIED, Oid.JSON_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_json"), "json[]", Oid.JSON_ARRAY, Types.ARRAY, Array.class, Oid.JSON, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "line"), "line", Oid.LINE, Types.OTHER, org.postgresql.geometric.PGline.class, Oid.FLOAT8, Oid.LINE_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_line"), "line[]", Oid.LINE_ARRAY, Types.ARRAY, Array.class, Oid.LINE, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "lseg"), "lseg", Oid.LSEG, Types.OTHER, org.postgresql.geometric.PGlseg.class, Oid.POINT, Oid.LSEG_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_lseg"), "lseg[]", Oid.LSEG_ARRAY, Types.ARRAY, Array.class, Oid.LSEG, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "money"), "money", Oid.MONEY, Types.DOUBLE, org.postgresql.util.PGmoney.class, Oid.UNSPECIFIED, Oid.MONEY_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_money"), "money[]", Oid.MONEY_ARRAY, Types.ARRAY, Array.class, Oid.MONEY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "name"), "name", Oid.NAME, Types.VARCHAR, String.class, Oid.CHAR, Oid.NAME_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_name"), "name[]", Oid.NAME_ARRAY, Types.ARRAY, Array.class, Oid.NAME, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "numeric"), "numeric", Oid.NUMERIC, Types.NUMERIC, BigDecimal.class, Oid.UNSPECIFIED, Oid.NUMERIC_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_numeric"), "numeric[]", Oid.NUMERIC_ARRAY, Types.ARRAY, Array.class, Oid.NUMERIC, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "oid"), "oid", Oid.OID, Types.BIGINT, Long.class, Oid.UNSPECIFIED, Oid.OID_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_oid"), "oid[]", Oid.OID_ARRAY, Types.ARRAY, Array.class, Oid.OID, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "path"), "path", Oid.PATH, Types.OTHER, org.postgresql.geometric.PGpath.class, Oid.UNSPECIFIED, Oid.PATH_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_path"), "path[]", Oid.PATH_ARRAY, Types.ARRAY, Array.class, Oid.PATH, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "point"), "point", Oid.POINT, Types.OTHER, org.postgresql.geometric.PGpoint.class, Oid.FLOAT8, Oid.POINT_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_point"), "point[]", Oid.POINT_ARRAY, Types.ARRAY, Array.class, Oid.POINT, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "polygon"), "polygon", Oid.POLYGON, Types.OTHER, org.postgresql.geometric.PGpolygon.class, Oid.UNSPECIFIED, Oid.POLYGON_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_polygon"), "polygon[]", Oid.POLYGON_ARRAY, Types.ARRAY, Array.class, Oid.POLYGON, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "refcursor"), "refcursor", Oid.REFCURSOR, Types.REF_CURSOR, java.sql.ResultSet.class, Oid.UNSPECIFIED, Oid.REFCURSOR_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_refcursor"), "refcursor[]", Oid.REFCURSOR_ARRAY, Types.ARRAY, Array.class, Oid.REFCURSOR, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "text"), "text", Oid.TEXT, Types.VARCHAR, String.class, Oid.UNSPECIFIED, Oid.TEXT_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_text"), "text[]", Oid.TEXT_ARRAY, Types.ARRAY, Array.class, Oid.TEXT, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "time"), "time without time zone", Oid.TIME, Types.TIME, java.sql.Time.class, Oid.UNSPECIFIED, Oid.TIME_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_time"), "time without time zone[]", Oid.TIME_ARRAY, Types.ARRAY, Array.class, Oid.TIME, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timestamp"), "timestamp without time zone", Oid.TIMESTAMP, Types.TIMESTAMP, java.sql.Timestamp.class, Oid.UNSPECIFIED, Oid.TIMESTAMP_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_timestamp"), "timestamp without time zone[]", Oid.TIMESTAMP_ARRAY, Types.ARRAY, Array.class, Oid.TIMESTAMP, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timestamptz"), "timestamp with time zone", Oid.TIMESTAMPTZ, Types.TIMESTAMP, java.sql.Timestamp.class, Oid.UNSPECIFIED, Oid.TIMESTAMPTZ_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_timestamptz"), "timestamp with time zone[]", Oid.TIMESTAMPTZ_ARRAY, Types.ARRAY, Array.class, Oid.TIMESTAMPTZ, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timetz"), "time with time zone", Oid.TIMETZ, Types.TIME, java.sql.Time.class, Oid.UNSPECIFIED, Oid.TIMETZ_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_timetz"), "time with time zone[]", Oid.TIMETZ_ARRAY, Types.ARRAY, Array.class, Oid.TIMETZ, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "uuid"), "uuid", Oid.UUID, Types.OTHER, java.util.UUID.class, Oid.UNSPECIFIED, Oid.UUID_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_uuid"), "uuid[]", Oid.UUID_ARRAY, Types.ARRAY, Array.class, Oid.UUID, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "varbit"), "bit varying", Oid.VARBIT, Types.OTHER, String.class, Oid.UNSPECIFIED, Oid.VARBIT_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_varbit"), "bit varying[]", Oid.VARBIT_ARRAY, Types.ARRAY, Array.class, Oid.VARBIT, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "varchar"), "character varying", Oid.VARCHAR, Types.VARCHAR, String.class, Oid.UNSPECIFIED, Oid.VARCHAR_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_varchar"), "character varying[]", Oid.VARCHAR_ARRAY, Types.ARRAY, Array.class, Oid.VARCHAR, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "xml"), "xml", Oid.XML, Types.SQLXML, java.sql.SQLXML.class, Oid.UNSPECIFIED, Oid.XML_ARRAY),
      new PgType(new ObjectName("pg_catalog", "_xml"), "xml[]", Oid.XML_ARRAY, Types.ARRAY, Array.class, Oid.XML, Oid.UNSPECIFIED),
  };

  /**
   * PG maps several alias to real type names. When we do queries against pg_catalog, we must use
   * the real type, not an alias, so use this mapping.
   *
   * <p>
   * Additional values used at runtime (including case variants) will be added to the map.
   * </p>
   */
  private static final ConcurrentMap<String, String> TYPE_ALIASES = new ConcurrentHashMap<>(30);

  static {
    TYPE_ALIASES.put("bool", "bool");
    TYPE_ALIASES.put("boolean", "bool");
    TYPE_ALIASES.put("smallint", "int2");
    TYPE_ALIASES.put("int2", "int2");
    TYPE_ALIASES.put("int", "int4");
    TYPE_ALIASES.put("integer", "int4");
    TYPE_ALIASES.put("int4", "int4");
    TYPE_ALIASES.put("long", "int8");
    TYPE_ALIASES.put("int8", "int8");
    TYPE_ALIASES.put("bigint", "int8");
    TYPE_ALIASES.put("float", "float8");
    TYPE_ALIASES.put("real", "float4");
    TYPE_ALIASES.put("float4", "float4");
    TYPE_ALIASES.put("double", "float8");
    TYPE_ALIASES.put("double precision", "float8");
    TYPE_ALIASES.put("float8", "float8");
    TYPE_ALIASES.put("decimal", "numeric");
    TYPE_ALIASES.put("numeric", "numeric");
    TYPE_ALIASES.put("character varying", "varchar");
    TYPE_ALIASES.put("varchar", "varchar");
    TYPE_ALIASES.put("time without time zone", "time");
    TYPE_ALIASES.put("time", "time");
    TYPE_ALIASES.put("time with time zone", "timetz");
    TYPE_ALIASES.put("timetz", "timetz");
    TYPE_ALIASES.put("timestamp without time zone", "timestamp");
    TYPE_ALIASES.put("timestamp", "timestamp");
    TYPE_ALIASES.put("timestamp with time zone", "timestamptz");
    TYPE_ALIASES.put("timestamptz", "timestamptz");
  }

  @SuppressWarnings("method.invocation")
  public TypeInfoCache(BaseConnection conn, int unknownLength) {
    this.conn = conn;
    this.unknownLength = unknownLength;
    int mapSize = (int) Math.round(BASE_TYPES.length * 1.5);
    typesByOid = new HashMap<>(mapSize);
    typesByPgName = new HashMap<>(mapSize);

    pgNameToPgObject = new HashMap<>(mapSize);

    try (ResourceLock ignore = lock.obtain()) {
      for (PgType type : BASE_TYPES) {
        addType(type);
      }
    }
  }

  private final ClassValue<AtomicInteger> arrayOid = new ClassValue<AtomicInteger>() {
    @Override
    protected AtomicInteger computeValue(Class<?> type) {
      return new AtomicInteger(Oid.UNSPECIFIED);
    }
  };

  private void addType(PgType pgType) {
    typesByOid.put(pgType.oid, pgType);
//     typesByPgName.put(pgType.typeName, pgType);
    if (pgType.javaClass != null) {
      arrayOid.get(pgType.javaClass)
          .compareAndSet(Oid.UNSPECIFIED, pgType.arrayOid);
    }
  }

  public void addDataType(String type, Class<? extends PGobject> klass)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      throw new UnsupportedOperationException();
//       pgNameToPgObject.put(type, klass);
//       pgNameToJavaClass.put(type, klass.getName());
    }
  }

  public Iterator<Integer> getPGTypeOidsWithSQLTypes() {
    throw new UnsupportedOperationException();
//     return oidToSQLType.keySet().iterator();
  }

  private static String getFindPgTypeQuery(String whereClause) {
    /* language=PostgreSQL */
    return "SELECT t.oid, t.typname, t.typcategory, t.typtype, t.typelem, t.typarray, n.nspname\n"
        + "    , pg_catalog.format_type(t.oid, null) fullName\n"
        + "  FROM pg_catalog.pg_type t\n"
        + " JOIN pg_catalog.pg_namespace n ON (t.typnamespace = n.oid)\n"
        + whereClause;
  }

  private static PgType mapToPgType(ResultSet rs) throws SQLException {
    String typcategory = rs.getString("typcategory");
    int sqlType = Types.OTHER;
    if ("A".equals(typcategory)) {
      sqlType = Types.ARRAY;
    } else if ("B".equals(typcategory)) {
      sqlType = Types.BOOLEAN;
    } else if ("N".equals(typcategory)) {
      sqlType = Types.NUMERIC;
    } else if ("S".equals(typcategory)) {
      sqlType = Types.VARCHAR;
    } else {
      String typtype = rs.getString("typtype");
      if ("c".equals(typtype)) {
        sqlType = Types.STRUCT;
      } else if ("d".equals(typtype)) {
        sqlType = Types.DISTINCT;
      } else if ("e".equals(typtype)) {
        sqlType = Types.VARCHAR;
      }
    }


    return new PgType(
        new ObjectName(rs.getString("nspname"), rs.getString("typname")),
        rs.getString("fullName"),
        rs.getInt("oid"),
        sqlType,
        sqlType == Types.ARRAY ? Array.class : null,
        rs.getInt("typelem"),
        rs.getInt("typarray"));
  }

  private PreparedStatement preparefindAllPgTypes() throws SQLException {
    PreparedStatement findAllPgTypes = this.findAllPgTypes;
    if (findAllPgTypes == null) {
      findAllPgTypes = conn.prepareStatement(getFindPgTypeQuery(""));
      this.findAllPgTypes = findAllPgTypes;
    }
    return findAllPgTypes;
  }

  private @Nullable PgType loadPgTypes(PreparedStatement preparedStatement) throws SQLException {
    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) preparedStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }
    PgType lastPgType = null;
    try (ResultSet rs = castNonNull(preparedStatement.getResultSet());) {
      while (rs.next()) {
        PgType type = mapToPgType(rs);
        lastPgType = type;
        addType(type);
      }
    }
    return lastPgType;
  }

  public void cacheSQLTypes() throws SQLException {
    LOGGER.log(Level.FINEST, "caching all SQL typecodes");
    loadPgTypes(preparefindAllPgTypes());
  }

  private PreparedStatement prepareFindPgTypeByOid() throws SQLException {
    PreparedStatement findPgTypeByOid = this.findPgTypeByOid;
    if (findPgTypeByOid == null) {
      String sql = getFindPgTypeQuery(" WHERE t.oid = ? ");
      findPgTypeByOid = conn.prepareStatement(sql);
      this.findPgTypeByOid = findPgTypeByOid;
    }
    return findPgTypeByOid;
  }

  public int getSQLType(String pgTypeName) throws SQLException {
    if (pgTypeName.endsWith("[]")) {
      return Types.ARRAY;
    }
    try (ResourceLock ignore = lock.obtain()) {
      return getPgTypeByPgName(pgTypeName).sqlType;
    }
  }

  @Override
  public int getJavaArrayType(Class<?> className) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      return arrayOid.get(className).get();
    }
  }

  public int getSQLType(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return Types.OTHER;
    }
    try (ResourceLock ignore = lock.obtain()) {
      return getPgTypeByOid(oid).sqlType;
    }
  }

  private PreparedStatement prepareFindPgTypeByPgName(String pgTypeName) throws SQLException {
    PreparedStatement findTypeStatement = this.findPgTypeByName;
    if (findTypeStatement == null) {
      String sql = getFindPgTypeQuery(" WHERE t.oid = ?::regtype");
      findTypeStatement = conn.prepareStatement(sql);
      this.findPgTypeByName = findTypeStatement;
    }
    findTypeStatement.setString(1, pgTypeName);
    return findTypeStatement;
  }

  private PgType getPgTypeByPgName(String pgTypeName) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      PgType pgType = typesByPgName.get(pgTypeName);
      if (pgType != null) {
        return pgType;
      }

      PreparedStatement findPgTypeByPgName = prepareFindPgTypeByPgName(pgTypeName);
      PgType res = loadPgTypes(findPgTypeByPgName);
      if (res == null) {
        throw new IllegalStateException("Type " + pgTypeName + " is not found");
      }
      typesByPgName.put(pgTypeName, res);
      return castNonNull(res);
    }
  }

  private PgType getPgTypeByOid(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      PgType pgType = typesByOid.get(oid);
      if (pgType != null) {
        return pgType;
      }

      PreparedStatement findPgTypeByOid = prepareFindPgTypeByOid();
      findPgTypeByOid.setInt(1, oid);
      return castNonNull(loadPgTypes(findPgTypeByOid));
    }
  }

  public int getPGType(String pgTypeName) throws SQLException {
    return getPgTypeByPgName(pgTypeName).oid;
  }

  public @Nullable String getPGType(int oid) throws SQLException {
    return getPgTypeByOid(oid).fullName;
  }

  public int getPGArrayType(@Nullable String elementTypeName) throws SQLException {
    elementTypeName = getTypeForAlias(elementTypeName);
    return getPgTypeByPgName(elementTypeName).arrayOid;
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
  protected int convertArrayToBaseOid(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      return getPgTypeByOid(oid).typelem;
    }
  }

  public char getArrayDelimiter(int oid) throws SQLException {
    return getPgTypeByOid(oid).delimiter;
  }

  public int getPGArrayElement(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (oid == Oid.UNSPECIFIED) {
        return Oid.UNSPECIFIED;
      }

      return getPgTypeByOid(oid).typelem;
    }
  }

  public @Nullable Class<? extends PGobject> getPGobject(String type) {
    try (ResourceLock ignore = lock.obtain()) {
      return pgNameToPgObject.get(type);
    }
  }

  public String getJavaClass(int oid) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      return getPgTypeByOid(oid).javaClass.getName();
    }
  }

  public @Nullable String getTypeForAlias(@Nullable String alias) {
    if ( alias == null ) {
      return null;
    }
    String type = TYPE_ALIASES.get(alias);
    if (type != null) {
      return type;
    }
    type = TYPE_ALIASES.get(alias.toLowerCase(Locale.ROOT));
    if (type == null) {
      type = alias;
    }
    //populate for future use
    TYPE_ALIASES.put(alias, type);
    return type;
  }

  public int getPrecision(int oid, int typmod) throws SQLException {
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
          return unknownLength;
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
          return unknownLength;
        }
        return typmod;

      case Oid.TEXT:
      case Oid.BYTEA:
      default:
        return unknownLength;
    }
  }

  public int getScale(int oid, int typmod) throws SQLException {
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

  public boolean isCaseSensitive(int oid) throws SQLException {
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

  public boolean isSigned(int oid) throws SQLException {
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

  public int getDisplaySize(int oid, int typmod) throws SQLException {
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
          default:
            throw new IllegalStateException("oid " + oid + " should not appear here");
        }
      case Oid.INTERVAL:
        // SELECT LENGTH('-123456789 years 11 months 33 days 23 hours 10.123456 seconds'::interval);
        return 49;
      case Oid.VARCHAR:
      case Oid.BPCHAR:
        if (typmod == -1) {
          return unknownLength;
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
          return unknownLength;
        }
        return typmod;
      case Oid.TEXT:
      case Oid.BYTEA:
        return unknownLength;
      default:
        return unknownLength;
    }
  }

  public int getMaximumPrecision(int oid) throws SQLException {
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
   * Returns true if particular sqlType requires quoting.
   * This method is used internally by the driver, so it might disappear without notice.
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

  @Override
  public int longOidToInt(long oid) throws SQLException {
    if ((oid & 0xFFFF_FFFF_0000_0000L) != 0) {
      throw new PSQLException(GT.tr("Value is not an OID: {0}", oid), PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }

    return (int) oid;
  }

  @Override
  public long intOidToLong(int oid) {
    return ((long) oid) & 0xFFFFFFFFL;
  }
}
