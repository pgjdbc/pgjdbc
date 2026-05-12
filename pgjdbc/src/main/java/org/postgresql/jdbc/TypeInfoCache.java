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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cache for PostgreSQL type information.
 *
 * <p>This class maintains a cache of PostgreSQL type metadata (PgType) and provides
 * methods to look up types by OID or name. The cache is automatically invalidated
 * when DDL commands (CREATE, DROP, ALTER) are executed in the current session.</p>
 *
 * <p><b>Known Limitation:</b> DDL changes (ALTER TYPE, DROP TYPE, CREATE TYPE)
 * executed in other database sessions are not detected until the next DDL command
 * is executed in the current session. This can result in stale type information
 * being used until the next DDL command triggers cache invalidation. Applications
 * that modify types concurrently from multiple connections should be aware of this
 * behavior.</p>
 */
public class TypeInfoCache implements TypeInfo {

  private static final Logger LOGGER = Logger.getLogger(TypeInfoCache.class.getName());

  public static final String PG_TYPE_FIELDS =
      "t.oid as typoid, t.typname, t.typcategory, t.typtype, t.typtypmod, t.typelem, t.typarray, t.typbasetype, t.typdelim, tn.nspname as typnspname, pg_catalog.format_type(t.oid, null) as typfullname";

  public static final String PG_TYPE_TABLE =
      "pg_catalog.pg_type t JOIN pg_catalog.pg_namespace tn ON (t.typnamespace = tn.oid)";

  /**
   * Enables to invalidate the caches if user executes create/drop type SQLs
   * @see QueryExecutor#getTypeCacheEpoch()
   */
  private int typeCacheEpoch;
  // Connection-specific oid -> PgType cache
  private final Map<Integer, PgType> typesByOid = new HashMap<>();
  // Connection-specific name -> PgType cache
  private final Map<String, PgType> typesByPgName = new HashMap<>();
  // Cache: oid -> visibility-aware display name (e.g. "int4" if on-path or
  // "\"Schema\".\"Type\"" if off-path). Computed lazily from
  // pg_type_is_visible. The legacy driver maintained the same map and
  // several callers (Array.getBaseTypeName, ResultSetMetaData.getColumnTypeName)
  // rely on its qualified-name semantics.
  private final Map<Integer, String> displayNameByOid = new HashMap<>();
  // Global oid -> PgType cache which includes only well-known types
  private static final Map<Integer, PgType> DEFAULT_TYPES_BY_OID;
  // Global name -> PgType cache which includes only well-known types
  private static final Map<String, PgType> DEFAULT_TYPES_BY_PGNAME;

  // Java type registry for Java ↔ PostgreSQL type mappings
  private final JavaTypeRegistry javaTypeRegistry = new JavaTypeRegistry();

  // Codec registry for type encoding/decoding
  private final CodecRegistry codecRegistry = new CodecRegistry();

  private final BaseConnection conn;
  private final int unknownLength;
  private @Nullable PreparedStatement findPgTypeByName;
  private @Nullable PreparedStatement findPgTypeByTypname;
  private @Nullable PreparedStatement findPgTypeByOid;
  private @Nullable PreparedStatement findAllPgTypes;
  private @Nullable PreparedStatement findCompositeFields;
  private @Nullable PreparedStatement findTypeVisibility;
  private final ResourceLock lock = new ResourceLock();

  // Note: this is generated with org.postgresql.jdbc.TypeInfoCacheTest.generateBaseTypes
  // Constructor: PgType(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype)
  private static final PgType[] BASE_TYPES = {
      // bit types (typcategory='V' bit-string)
      new PgType(new ObjectName("pg_catalog", "bit"), "bit", Oid.BIT, 'b', 'V', -1, Oid.UNSPECIFIED, Oid.BIT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bit"), "bit[]", Oid.BIT_ARRAY, 'b', 'A', -1, Oid.BIT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // boolean (typcategory='B')
      new PgType(new ObjectName("pg_catalog", "bool"), "boolean", Oid.BOOL, 'b', 'B', -1, Oid.UNSPECIFIED, Oid.BOOL_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bool"), "boolean[]", Oid.BOOL_ARRAY, 'b', 'A', -1, Oid.BOOL, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // geometric types (typcategory='G')
      new PgType(new ObjectName("pg_catalog", "box"), "box", Oid.BOX, 'b', 'G', -1, Oid.POINT, Oid.BOX_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_box"), "box[]", Oid.BOX_ARRAY, 'b', 'A', -1, Oid.BOX, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // string types (typcategory='S')
      new PgType(new ObjectName("pg_catalog", "bpchar"), "character", Oid.BPCHAR, 'b', 'S', -1, Oid.UNSPECIFIED, Oid.BPCHAR_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bpchar"), "character[]", Oid.BPCHAR_ARRAY, 'b', 'A', -1, Oid.BPCHAR, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // user-defined types (typcategory='U')
      new PgType(new ObjectName("pg_catalog", "bytea"), "bytea", Oid.BYTEA, 'b', 'U', -1, Oid.UNSPECIFIED, Oid.BYTEA_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bytea"), "bytea[]", Oid.BYTEA_ARRAY, 'b', 'A', -1, Oid.BYTEA, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // geometric types (typcategory='G')
      new PgType(new ObjectName("pg_catalog", "circle"), "circle", Oid.CIRCLE, 'b', 'G', -1, Oid.UNSPECIFIED, Oid.CIRCLE_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_circle"), "circle[]", Oid.CIRCLE_ARRAY, 'b', 'A', -1, Oid.CIRCLE, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // date/time types (typcategory='D')
      new PgType(new ObjectName("pg_catalog", "date"), "date", Oid.DATE, 'b', 'D', -1, Oid.UNSPECIFIED, Oid.DATE_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_date"), "date[]", Oid.DATE_ARRAY, 'b', 'A', -1, Oid.DATE, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // numeric types (typcategory='N')
      new PgType(new ObjectName("pg_catalog", "float4"), "real", Oid.FLOAT4, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.FLOAT4_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_float4"), "real[]", Oid.FLOAT4_ARRAY, 'b', 'A', -1, Oid.FLOAT4, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "float8"), "double precision", Oid.FLOAT8, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.FLOAT8_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_float8"), "double precision[]", Oid.FLOAT8_ARRAY, 'b', 'A', -1, Oid.FLOAT8, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // numeric types (typcategory='N')
      new PgType(new ObjectName("pg_catalog", "int2"), "smallint", Oid.INT2, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.INT2_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_int2"), "smallint[]", Oid.INT2_ARRAY, 'b', 'A', -1, Oid.INT2, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int4"), "integer", Oid.INT4, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.INT4_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_int4"), "integer[]", Oid.INT4_ARRAY, 'b', 'A', -1, Oid.INT4, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int8"), "bigint", Oid.INT8, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.INT8_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_int8"), "bigint[]", Oid.INT8_ARRAY, 'b', 'A', -1, Oid.INT8, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // timespan types (typcategory='T')
      new PgType(new ObjectName("pg_catalog", "interval"), "interval", Oid.INTERVAL, 'b', 'T', -1, Oid.UNSPECIFIED, Oid.INTERVAL_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_interval"), "interval[]", Oid.INTERVAL_ARRAY, 'b', 'A', -1, Oid.INTERVAL, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // user-defined types (typcategory='U')
      new PgType(new ObjectName("pg_catalog", "json"), "json", Oid.JSON, 'b', 'U', -1, Oid.UNSPECIFIED, Oid.JSON_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_json"), "json[]", Oid.JSON_ARRAY, 'b', 'A', -1, Oid.JSON, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // geometric types (typcategory='G')
      new PgType(new ObjectName("pg_catalog", "line"), "line", Oid.LINE, 'b', 'G', -1, Oid.FLOAT8, Oid.LINE_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_line"), "line[]", Oid.LINE_ARRAY, 'b', 'A', -1, Oid.LINE, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "lseg"), "lseg", Oid.LSEG, 'b', 'G', -1, Oid.POINT, Oid.LSEG_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_lseg"), "lseg[]", Oid.LSEG_ARRAY, 'b', 'A', -1, Oid.LSEG, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // money (typcategory='N' numeric) - special case, PG actually uses typcategory 'N' but pgjdbc maps it to DOUBLE
      new PgType(new ObjectName("pg_catalog", "money"), "money", Oid.MONEY, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.MONEY_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_money"), "money[]", Oid.MONEY_ARRAY, 'b', 'A', -1, Oid.MONEY, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // string types (typcategory='S')
      new PgType(new ObjectName("pg_catalog", "name"), "name", Oid.NAME, 'b', 'S', -1, Oid.CHAR, Oid.NAME_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_name"), "name[]", Oid.NAME_ARRAY, 'b', 'A', -1, Oid.NAME, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // numeric types (typcategory='N')
      new PgType(new ObjectName("pg_catalog", "numeric"), "numeric", Oid.NUMERIC, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.NUMERIC_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_numeric"), "numeric[]", Oid.NUMERIC_ARRAY, 'b', 'A', -1, Oid.NUMERIC, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "oid"), "oid", Oid.OID, 'b', 'N', -1, Oid.UNSPECIFIED, Oid.OID_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_oid"), "oid[]", Oid.OID_ARRAY, 'b', 'A', -1, Oid.OID, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // geometric types (typcategory='G')
      new PgType(new ObjectName("pg_catalog", "path"), "path", Oid.PATH, 'b', 'G', -1, Oid.UNSPECIFIED, Oid.PATH_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_path"), "path[]", Oid.PATH_ARRAY, 'b', 'A', -1, Oid.PATH, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "point"), "point", Oid.POINT, 'b', 'G', -1, Oid.FLOAT8, Oid.POINT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_point"), "point[]", Oid.POINT_ARRAY, 'b', 'A', -1, Oid.POINT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "polygon"), "polygon", Oid.POLYGON, 'b', 'G', -1, Oid.UNSPECIFIED, Oid.POLYGON_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_polygon"), "polygon[]", Oid.POLYGON_ARRAY, 'b', 'A', -1, Oid.POLYGON, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // user-defined types (typcategory='U')
      new PgType(new ObjectName("pg_catalog", "refcursor"), "refcursor", Oid.REFCURSOR, 'b', 'U', -1, Oid.UNSPECIFIED, Oid.REFCURSOR_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_refcursor"), "refcursor[]", Oid.REFCURSOR_ARRAY, 'b', 'A', -1, Oid.REFCURSOR, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // string types (typcategory='S')
      new PgType(new ObjectName("pg_catalog", "text"), "text", Oid.TEXT, 'b', 'S', -1, Oid.UNSPECIFIED, Oid.TEXT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_text"), "text[]", Oid.TEXT_ARRAY, 'b', 'A', -1, Oid.TEXT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // date/time types (typcategory='D')
      new PgType(new ObjectName("pg_catalog", "time"), "time without time zone", Oid.TIME, 'b', 'D', -1, Oid.UNSPECIFIED, Oid.TIME_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_time"), "time without time zone[]", Oid.TIME_ARRAY, 'b', 'A', -1, Oid.TIME, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timestamp"), "timestamp without time zone", Oid.TIMESTAMP, 'b', 'D', -1, Oid.UNSPECIFIED, Oid.TIMESTAMP_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_timestamp"), "timestamp without time zone[]", Oid.TIMESTAMP_ARRAY, 'b', 'A', -1, Oid.TIMESTAMP, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timestamptz"), "timestamp with time zone", Oid.TIMESTAMPTZ, 'b', 'D', -1, Oid.UNSPECIFIED, Oid.TIMESTAMPTZ_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_timestamptz"), "timestamp with time zone[]", Oid.TIMESTAMPTZ_ARRAY, 'b', 'A', -1, Oid.TIMESTAMPTZ, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timetz"), "time with time zone", Oid.TIMETZ, 'b', 'D', -1, Oid.UNSPECIFIED, Oid.TIMETZ_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_timetz"), "time with time zone[]", Oid.TIMETZ_ARRAY, 'b', 'A', -1, Oid.TIMETZ, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // user-defined types (typcategory='U')
      new PgType(new ObjectName("pg_catalog", "uuid"), "uuid", Oid.UUID, 'b', 'U', -1, Oid.UNSPECIFIED, Oid.UUID_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_uuid"), "uuid[]", Oid.UUID_ARRAY, 'b', 'A', -1, Oid.UUID, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // bit-string types (typcategory='V')
      new PgType(new ObjectName("pg_catalog", "varbit"), "bit varying", Oid.VARBIT, 'b', 'V', -1, Oid.UNSPECIFIED, Oid.VARBIT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_varbit"), "bit varying[]", Oid.VARBIT_ARRAY, 'b', 'A', -1, Oid.VARBIT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // string types (typcategory='S')
      new PgType(new ObjectName("pg_catalog", "varchar"), "character varying", Oid.VARCHAR, 'b', 'S', -1, Oid.UNSPECIFIED, Oid.VARCHAR_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_varchar"), "character varying[]", Oid.VARCHAR_ARRAY, 'b', 'A', -1, Oid.VARCHAR, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      // user-defined types (typcategory='U')
      new PgType(new ObjectName("pg_catalog", "xml"), "xml", Oid.XML, 'b', 'U', -1, Oid.UNSPECIFIED, Oid.XML_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_xml"), "xml[]", Oid.XML_ARRAY, 'b', 'A', -1, Oid.XML, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
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
    Map<Integer, PgType> typesByOid = new HashMap<>((int)(BASE_TYPES.length / 0.75f));
    Map<String, PgType> typesByPgName = new HashMap<>((int)(2 * BASE_TYPES.length / 0.75f));
    for (PgType type : BASE_TYPES) {
      typesByOid.put(type.getOid(), type);
      // TODO: double-check if we should have fullName or quoted "user"."object_name" or both
      typesByPgName.put(type.getFullName(), type);
      // Allow both lowercase and uppercase lookups
      typesByPgName.put(type.getFullName().toUpperCase(Locale.ROOT), type);
    }
    DEFAULT_TYPES_BY_OID = typesByOid;
    DEFAULT_TYPES_BY_PGNAME = typesByPgName;
    // TODO: do we need something like DEFAULT_arrayOid?
  }

  @SuppressWarnings("method.invocation")
  public TypeInfoCache(BaseConnection conn, int unknownLength) {
    this.conn = conn;
    this.unknownLength = unknownLength;
  }

  /**
   * Gets the Java type registry for this connection.
   *
   * @return the Java type registry
   */
  @Override
  public JavaTypeRegistry getJavaTypeRegistry() {
    return javaTypeRegistry;
  }

  /**
   * Gets the codec registry for this connection.
   *
   * @return the codec registry
   */
  @Override
  public CodecRegistry getCodecRegistry() {
    return codecRegistry;
  }

  @Override
  public void add(PgType pgType) {
    typesByOid.put(pgType.getOid(), pgType);
    // Register array OID mapping for the Java class
    int arrayOid = pgType.getArrayOid();
    if (arrayOid != Oid.UNSPECIFIED) {
      Class<?> javaClass = JavaTypeRegistry.getDefaultJavaClass(pgType.getOid());
      javaTypeRegistry.registerArrayOid(javaClass, arrayOid);
    }
  }

  @Override
  public void addDataType(String type, Class<? extends PGobject> klass)
      throws SQLException {
    javaTypeRegistry.addPGobject(type, klass);
  }

  @Override
  @SuppressWarnings("deprecation")
  public Iterator<Integer> getPGTypeOidsWithSQLTypes() {
    throw new UnsupportedOperationException();
  }

  private static String getFindPgTypeQuery(String whereClause) {
    /* language=PostgreSQL */
    return "SELECT " + PG_TYPE_FIELDS + "\n"
        + "  FROM " + PG_TYPE_TABLE + "\n"
        + whereClause;
  }

  public static PgType mapToPgType(ResultSet rs) throws SQLException {
    String typcategoryStr = rs.getString("typcategory");
    String typtypeStr = rs.getString("typtype");
    String typdelimStr = rs.getString("typdelim");
    char typcategory = typcategoryStr != null && !typcategoryStr.isEmpty() ? typcategoryStr.charAt(0) : 'X';
    char typtype = typtypeStr != null && !typtypeStr.isEmpty() ? typtypeStr.charAt(0) : 'b';
    char typdelim = typdelimStr != null && !typdelimStr.isEmpty() ? typdelimStr.charAt(0) : ',';
    int oid = rs.getInt("typoid");

    String typName = castNonNull(rs.getString("typname"));
    String typFullName = castNonNull(rs.getString("typfullname"));
    return new PgType(
        new ObjectName(rs.getString("typnspname"), typName),
        typFullName,
        oid,
        typtype,
        typcategory,
        rs.getInt("typtypmod"),
        rs.getInt("typelem"),
        rs.getInt("typarray"),
        rs.getInt("typbasetype"),
        typdelim);
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
        add(type);
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

  @Override
  @SuppressWarnings("deprecation")
  public int getJavaArrayType(Class<?> javaClass) throws SQLException {
    return javaTypeRegistry.getArrayOidForJavaClass(javaClass);
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

  private void invalidateCacheIfNeeded() {
    int typeCacheEpoch = this.typeCacheEpoch;
    int connectionTypeCacheEpoch = conn.getTypeCacheEpoch();
    if (typeCacheEpoch == connectionTypeCacheEpoch) {
      // All good
      return;
    }
    // Epoch mismatch, invalidating the caches
    typesByPgName.clear();
    typesByOid.clear();
    displayNameByOid.clear();
    codecRegistry.invalidateCache();
    // Update epoch after clearing to prevent repeated invalidations
    this.typeCacheEpoch = connectionTypeCacheEpoch;
  }

  @Override
  public PgType getPgTypeByPgName(String pgTypeName) throws SQLException {
    pgTypeName = castNonNull(getTypeForAlias(pgTypeName));
    PgType pgType = DEFAULT_TYPES_BY_PGNAME.get(pgTypeName);
    if (pgType != null) {
      return pgType;
    }
    try (ResourceLock ignore = lock.obtain()) {
      invalidateCacheIfNeeded();
      pgType = typesByPgName.get(pgTypeName);
      if (pgType != null) {
        return pgType;
      }

      LOGGER.log(Level.FINEST, "querying SQL typecode for pg type {0}", pgTypeName);
      PreparedStatement findPgTypeByPgName = prepareFindPgTypeByPgName(pgTypeName);
      PgType res;
      try {
        res = loadPgTypes(findPgTypeByPgName);
      } catch (PSQLException e) {
        // regtype cast can fail for names outside the current search_path
        // (e.g. SearchPathLookupTest exercises the back-compat path that
        // resolves the most recently created type by typname alone). Fall
        // back to a plain typname lookup before giving up.
        res = null;
      }
      if (res == null) {
        // Strip enclosing quotes (and trailing schema-qualifier) so the
        // typname comparison sees the raw identifier as stored in pg_type.
        String fallbackName = pgTypeName;
        int lastDot = fallbackName.lastIndexOf('.');
        if (lastDot >= 0) {
          fallbackName = fallbackName.substring(lastDot + 1);
        }
        if (fallbackName.length() >= 2
            && fallbackName.charAt(0) == '"'
            && fallbackName.charAt(fallbackName.length() - 1) == '"') {
          fallbackName = fallbackName.substring(1, fallbackName.length() - 1);
        }
        PreparedStatement fallback = prepareFindPgTypeByTypname();
        fallback.setString(1, fallbackName);
        res = loadPgTypes(fallback);
      }
      if (res == null) {
        throw new PSQLException(GT.tr("Unknown type {0}.", pgTypeName),
            PSQLState.INVALID_PARAMETER_TYPE);
      }
      typesByPgName.put(pgTypeName, res);
      return castNonNull(res);
    }
  }

  /**
   * Resolves the display name for the given type OID using the legacy
   * driver's "qualified-when-not-visible" rule:
   *
   * <ul>
   *   <li>If the type is reachable via the current search_path with the bare
   *       {@code typname}, return {@code typname} (e.g. "int4").</li>
   *   <li>Otherwise return {@code "schema"."typname"} (quoted, qualified)
   *       so the result is unambiguous (e.g.
   *       {@code "Composites"."ComplexCompositeTest"}).</li>
   * </ul>
   *
   * <p>The result is cached per-connection and invalidated together with the
   * rest of the type cache (any CREATE/DROP/ALTER/SET search_path).</p>
   *
   * @param oid the type OID
   * @return the display name, or null if the OID does not refer to a type
   * @throws SQLException if the visibility lookup fails
   */
  public @Nullable String getPGTypeDisplayName(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return null;
    }
    try (ResourceLock ignore = lock.obtain()) {
      invalidateCacheIfNeeded();
      String cached = displayNameByOid.get(oid);
      if (cached != null) {
        return cached;
      }
      PreparedStatement ps = prepareFindTypeVisibility();
      ps.setInt(1, oid);
      if (!((BaseStatement) ps).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
        return null;
      }
      try (ResultSet rs = castNonNull(ps.getResultSet())) {
        if (!rs.next()) {
          return null;
        }
        String typname = castNonNull(rs.getString(1));
        String nspname = castNonNull(rs.getString(2));
        boolean visible = rs.getBoolean(3);
        String display = visible ? typname : "\"" + nspname + "\".\"" + typname + "\"";
        displayNameByOid.put(oid, display);
        return display;
      }
    }
  }

  private PreparedStatement prepareFindTypeVisibility() throws SQLException {
    PreparedStatement ps = this.findTypeVisibility;
    if (ps == null) {
      ps = conn.prepareStatement(
          "SELECT t.typname, n.nspname, pg_catalog.pg_type_is_visible(t.oid) "
              + "FROM pg_catalog.pg_type t "
              + "JOIN pg_catalog.pg_namespace n ON (t.typnamespace = n.oid) "
              + "WHERE t.oid = ?");
      this.findTypeVisibility = ps;
    }
    return ps;
  }

  private PreparedStatement prepareFindPgTypeByTypname() throws SQLException {
    PreparedStatement findTypeStatement = this.findPgTypeByTypname;
    if (findTypeStatement == null) {
      String sql = getFindPgTypeQuery(
          " WHERE t.typname = ? ORDER BY t.oid DESC LIMIT 1");
      findTypeStatement = conn.prepareStatement(sql);
      this.findPgTypeByTypname = findTypeStatement;
    }
    return findTypeStatement;
  }

  @Override
  public PgType getPgTypeByOid(int oid) throws SQLException {
    PgType pgType = DEFAULT_TYPES_BY_OID.get(oid);
    if (pgType != null) {
      return pgType;
    }
    try (ResourceLock ignore = lock.obtain()) {
      invalidateCacheIfNeeded();
      pgType = typesByOid.get(oid);
      if (pgType != null) {
        return pgType;
      }

      PreparedStatement findPgTypeByOid = prepareFindPgTypeByOid();
      findPgTypeByOid.setInt(1, oid);
      PgType loaded = loadPgTypes(findPgTypeByOid);
      if (loaded == null) {
        throw new PSQLException(
            GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
      }
      return loaded;
    }
  }

  @Override
  public @Nullable Class<? extends PGobject> getPGobject(String type) {
    return javaTypeRegistry.getPGobject(type);
  }

  @Override
  @SuppressWarnings("deprecation")
  public @Nullable String getTypeForAlias(@Nullable String alias) {
    if (alias == null) {
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

  public static int estimateMaxLength(int oid, short typlen, int typmod) {
    if (typlen >= 0) {
      return typlen;
    }
    switch (oid) {
      case Oid.BPCHAR:
      case Oid.VARCHAR:
      case Oid.VARBIT:
        if (typmod == -1) {
          return -1;
        }
        return typmod - 4;
      case Oid.NUMERIC:
        if (typmod == -1) {
          return -1;
        }
        int precision = (typmod - 4 >> 16) & 0xffff;
        // The actual storage requirement is two bytes for each group of four decimal digits,
        // plus three to eight bytes overhead.
        return 8 + precision / 2;
      case Oid.BIT:
      case Oid.CHAR:
        return typmod;
      default:
        return -1;
    }
  }

  @Override
  public int getPrecision(int oid, int typmod) throws SQLException {
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

  @Override
  public int getScale(int oid, int typmod) throws SQLException {
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

  @Override
  public int getDisplaySize(int oid, int typmod) throws SQLException {
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

  @Override
  public int getMaximumPrecision(int oid) throws SQLException {
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

  /**
   * Gets the fields for a composite type.
   * Fields are loaded eagerly when first accessed and cached.
   *
   * @param oid the OID of the composite type
   * @return the list of fields, or an empty list if not a composite type
   * @throws SQLException if a database error occurs
   */
  @Override
  public List<PgField> getFields(int oid) throws SQLException {
    PgType pgType = getPgTypeByOid(oid);
    if (!pgType.isComposite()) {
      return java.util.Collections.emptyList();
    }

    List<PgField> fields = pgType.getFields();
    if (fields != null) {
      return fields;
    }

    // Fields not loaded yet, load them now
    try (ResourceLock ignore = lock.obtain()) {
      // Double-check after acquiring lock - check connection cache first
      PgType cachedType = typesByOid.get(oid);
      if (cachedType != null) {
        List<PgField> cachedFields = cachedType.getFields();
        if (cachedFields != null) {
          return cachedFields;
        }
      }

      fields = loadCompositeFields(oid);

      // Update the cached type with fields
      // Use cachedType if available, otherwise use the original pgType
      // (which may be from DEFAULT_TYPES_BY_OID for built-in types)
      PgType baseType = cachedType != null ? cachedType : pgType;
      PgType updatedType = baseType.withFields(fields);
      typesByOid.put(oid, updatedType);

      return fields;
    }
  }

  private PreparedStatement prepareFindCompositeFields() throws SQLException {
    PreparedStatement stmt = this.findCompositeFields;
    if (stmt == null) {
      /* language=PostgreSQL */
      String sql = "SELECT a.attname, a.atttypid, a.attnum, a.atttypmod\n"
          + "FROM pg_catalog.pg_type t\n"
          + "JOIN pg_catalog.pg_attribute a ON (a.attrelid = t.typrelid)\n"
          + "WHERE t.oid = ?\n"
          + "  AND a.attnum > 0\n"
          + "  AND NOT a.attisdropped\n"
          + "ORDER BY a.attnum";
      stmt = conn.prepareStatement(sql);
      this.findCompositeFields = stmt;
    }
    return stmt;
  }

  private List<PgField> loadCompositeFields(int typeOid) throws SQLException {
    PreparedStatement stmt = prepareFindCompositeFields();
    stmt.setInt(1, typeOid);

    List<PgField> fields = new ArrayList<>();
    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) stmt).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      return fields;
    }

    try (ResultSet rs = castNonNull(stmt.getResultSet())) {
      while (rs.next()) {
        String name = castNonNull(rs.getString("attname"));
        int fieldTypeOid = rs.getInt("atttypid");
        int position = rs.getInt("attnum");
        int typmod = rs.getInt("atttypmod");
        fields.add(new PgField(name, fieldTypeOid, position, typmod));
      }
    }

    return fields;
  }
}
