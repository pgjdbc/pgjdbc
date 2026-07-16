/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.Codec;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.Oid;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.codec.PGobjectCodec;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

  // typsend/typreceive (regproc) are cast to text so their result column is a built-in type. regproc
  // is not in BASE_TYPES, so under binaryTransferEnable=* the column comes back in binary and reading
  // it via getString re-enters this cache to resolve regproc — which is not loaded yet — recursing
  // until the stack overflows. The cast runs the same regproc output the driver already relies on
  // (the send/receive function name, or "-" when absent), so the value is unchanged; only the wire
  // type changes. The "char" columns (typcategory, typtype, typdelim) need no cast: "char" is a
  // built-in type (CharCodec), so it resolves and decodes in binary without a catalog round-trip.
  public static final String PG_TYPE_FIELDS =
      "t.oid as typoid, t.typname, t.typcategory, t.typtype, t.typtypmod, t.typelem, t.typarray, t.typbasetype, t.typdelim, t.typsend::text as typsend, t.typreceive::text as typreceive, tn.nspname as typnspname, pg_catalog.format_type(t.oid, null) as typfullname";

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
  private @Nullable PreparedStatement findRangeSubtype;
  private @Nullable PreparedStatement findMultirangeRange;
  private @Nullable PreparedStatement findTypeVisibility;
  private final ResourceLock lock = new ResourceLock();

  // Memoized result of backendCanSendBinary(), keyed by type OID. Recursion into
  // element/field/base types makes the first computation per OID non-trivial.
  private final Map<Integer, Boolean> binarySendCapable = new ConcurrentHashMap<>();

  // Memoized result of backendCanReceiveBinary(), keyed by type OID. Mirrors
  // binarySendCapable for the send direction: a type may have a binary output
  // (typsend) but no binary input (typreceive), so the server could send it in
  // binary yet reject a binary parameter of that type.
  private final Map<Integer, Boolean> binaryReceiveServerCapable = new ConcurrentHashMap<>();

  // Memoized result of driverCanReceiveBinary(), keyed by type OID. Mirrors
  // binarySendCapable: the server may be able to send a type in binary while the
  // driver has no binary decoder for it (an unmapped type, resolved to FallbackCodec).
  private final Map<Integer, Boolean> binaryReceiveCodecCapable = new ConcurrentHashMap<>();

  // OIDs the connection creator opted out of binary receive (binaryTransferDisable).
  // Effectively immutable once the connection is set up.
  private volatile Set<Integer> binaryReceiveDisabledOids = Collections.emptySet();

  // Memoized result of isBinaryReceiveDisabled(): an OID is tainted when it, or any
  // element/field/base type it contains, is in binaryReceiveDisabledOids.
  private final Map<Integer, Boolean> binaryReceiveDisabled = new ConcurrentHashMap<>();

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
    Map<Integer, PgType> typesByOid = new HashMap<>((int)(BaseTypes.BASE_TYPES.length / 0.75f));
    Map<String, PgType> typesByPgName = new HashMap<>((int)(2 * BaseTypes.BASE_TYPES.length / 0.75f));
    for (PgType type : BaseTypes.BASE_TYPES) {
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

  /**
   * Returns the built-in {@link PgType} for a well-known OID, or {@code null} when the OID is not a
   * driver-known built-in type. This is the static catalog seeded into every cache, exposed so the
   * connectionless (offline) codec context can resolve built-in scalar, temporal and array types
   * without a live type cache.
   *
   * @param oid the type OID
   * @return the built-in type descriptor, or {@code null} if the OID is not a built-in type
   */
  static @Nullable PgType getDefaultType(int oid) {
    return DEFAULT_TYPES_BY_OID.get(oid);
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
    // Install a codec adapter keyed by OID so the registration takes effect
    // wherever the codec layer resolves the type — top-level columns, array
    // elements and composite fields — independent of the identifier form used
    // to register it. If the type is not present yet, the name-based registry
    // above still applies through Connection#getObject(String, ...).
    try {
      PgType pgType = getPgTypeByPgName(type);
      int oid = pgType.getOid();
      if (oid != Oid.UNSPECIFIED) {
        Codec delegate = codecRegistry.getByOid(oid, pgType);
        codecRegistry.registerByOid(oid, new PGobjectCodec(klass, delegate));
      }
    } catch (SQLException ignore) {
      // Type not resolvable yet; resolution falls back to the name-based registry.
    }
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
    String typsend = rs.getString("typsend");
    if (typsend == null || typsend.isEmpty()) {
      typsend = "-";
    }
    String typreceive = rs.getString("typreceive");
    if (typreceive == null || typreceive.isEmpty()) {
      typreceive = "-";
    }
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
        typdelim,
        typsend,
        typreceive);
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
    binarySendCapable.clear();
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

  /**
   * Gets the subtype OID of a range type, loading {@code pg_range.rngsubtype} lazily and
   * caching it on the {@link PgType}. Mirrors {@link #getFields(int)} for composites.
   *
   * @param oid the OID of the range type
   * @return the subtype OID, or {@link Oid#UNSPECIFIED} if the type is not a range
   * @throws SQLException if a database error occurs
   */
  @Override
  public int getRangeSubtype(int oid) throws SQLException {
    PgType pgType = getPgTypeByOid(oid);
    // Only base ranges (typtype 'r') carry a scalar subtype here. A multirange ('m') links to a
    // range, not a scalar; resolve it with getMultirangeRange(int), then this on that range.
    if (pgType.getTyptype() != 'r') {
      return Oid.UNSPECIFIED;
    }

    int subtype = pgType.getRangeSubtype();
    if (subtype != Oid.UNSPECIFIED) {
      return subtype;
    }

    // Subtype not loaded yet, load it now
    try (ResourceLock ignore = lock.obtain()) {
      // Double-check after acquiring the lock - check the connection cache first
      PgType cachedType = typesByOid.get(oid);
      if (cachedType != null) {
        int cachedSubtype = cachedType.getRangeSubtype();
        if (cachedSubtype != Oid.UNSPECIFIED) {
          return cachedSubtype;
        }
      }

      subtype = loadRangeSubtype(oid);

      // Cache the subtype on the type. Use cachedType if available, otherwise the
      // original pgType (which may be from DEFAULT_TYPES_BY_OID for built-in ranges).
      PgType baseType = cachedType != null ? cachedType : pgType;
      typesByOid.put(oid, baseType.withRangeSubtype(subtype));

      return subtype;
    }
  }

  /**
   * Gets the range type OID of a multirange type, loading {@code pg_range.rngtypid} (joined on
   * {@code rngmultitypid}) lazily and caching it on the {@link PgType}. Mirrors
   * {@link #getRangeSubtype(int)} for ranges.
   *
   * @param oid the OID of the multirange type
   * @return the range type OID, or {@link Oid#UNSPECIFIED} if the type is not a multirange
   * @throws SQLException if a database error occurs
   */
  @Override
  public int getMultirangeRange(int oid) throws SQLException {
    PgType pgType = getPgTypeByOid(oid);
    if (pgType.getTyptype() != 'm') {
      return Oid.UNSPECIFIED;
    }

    int range = pgType.getMultirangeRange();
    if (range != Oid.UNSPECIFIED) {
      return range;
    }

    // Range type not loaded yet, load it now
    try (ResourceLock ignore = lock.obtain()) {
      // Double-check after acquiring the lock - check the connection cache first
      PgType cachedType = typesByOid.get(oid);
      if (cachedType != null) {
        int cachedRange = cachedType.getMultirangeRange();
        if (cachedRange != Oid.UNSPECIFIED) {
          return cachedRange;
        }
      }

      range = loadMultirangeRange(oid);

      // Cache the range type on the type. Multiranges are never in DEFAULT_TYPES_BY_OID, so
      // cachedType is the type loaded from the catalog; fall back to pgType for safety.
      PgType baseType = cachedType != null ? cachedType : pgType;
      typesByOid.put(oid, baseType.withMultirangeRange(range));

      return range;
    }
  }

  @Override
  public boolean backendCanSendBinary(PgType type) throws SQLException {
    Boolean cached = binarySendCapable.get(type.getOid());
    if (cached != null) {
      return cached;
    }
    boolean result = computeBackendCanSendBinary(type);
    binarySendCapable.put(type.getOid(), result);
    return result;
  }

  @Override
  public boolean backendCanReceiveBinary(PgType type) throws SQLException {
    Boolean cached = binaryReceiveServerCapable.get(type.getOid());
    if (cached != null) {
      return cached;
    }
    boolean result = computeBackendCanReceiveBinary(type);
    binaryReceiveServerCapable.put(type.getOid(), result);
    return result;
  }

  @Override
  public boolean driverCanReceiveBinary(PgType type) throws SQLException {
    Boolean cached = binaryReceiveCodecCapable.get(type.getOid());
    if (cached != null) {
      return cached;
    }
    boolean result = computeDriverCanReceiveBinary(type);
    binaryReceiveCodecCapable.put(type.getOid(), result);
    return result;
  }

  @Override
  public void setBinaryReceiveDisabledOids(Set<? extends Integer> oids) {
    this.binaryReceiveDisabledOids = oids.isEmpty()
        ? Collections.emptySet()
        : Collections.unmodifiableSet(new HashSet<>(oids));
    binaryReceiveDisabled.clear();
  }

  @Override
  public boolean isBinaryReceiveDisabled(PgType type) throws SQLException {
    Boolean cached = binaryReceiveDisabled.get(type.getOid());
    if (cached != null) {
      return cached;
    }
    boolean result = computeBinaryReceiveDisabled(type);
    binaryReceiveDisabled.put(type.getOid(), result);
    return result;
  }

  private boolean computeBinaryReceiveDisabled(PgType type) throws SQLException {
    // A direct opt-out, or — recursively — any element/field/base type opted out,
    // taints the whole column: there is no per-field transfer format, so a disabled
    // type nested in a binary column would still be decoded in binary.
    if (binaryReceiveDisabledOids.contains(type.getOid())) {
      return true;
    }
    if (binaryReceiveDisabledOids.isEmpty()) {
      return false;
    }
    if (type.isArray()) {
      int elementOid = type.getTypelem();
      return elementOid != Oid.UNSPECIFIED && isBinaryReceiveDisabled(getPgTypeByOid(elementOid));
    }
    // Anonymous record: per-value field types are unknown from the catalog, so only
    // the direct opt-out above applies.
    if (type.getOid() == Oid.RECORD) {
      return false;
    }
    if (type.isComposite()) {
      for (PgField field : getFields(type.getOid())) {
        if (isBinaryReceiveDisabled(getPgTypeByOid(field.getTypeOid()))) {
          return true;
        }
      }
      return false;
    }
    if (type.isDomain()) {
      int baseOid = type.getTypbasetype();
      return baseOid != Oid.UNSPECIFIED && isBinaryReceiveDisabled(getPgTypeByOid(baseOid));
    }
    return false;
  }

  @Override
  public boolean shouldReceiveBinary(int oid) {
    // Anonymous record is optimistic and resolved without catalog I/O, so a
    // forced-binary first execution can request binary before any result set has
    // warmed the memo. A direct binaryTransferDisable opt-out still applies.
    if (oid == Oid.RECORD || oid == Oid.RECORD_ARRAY) {
      return !binaryReceiveDisabledOids.contains(oid);
    }
    // A column is requested in binary only when the server can send it
    // (backendCanSendBinary), the driver can decode it (driverCanReceiveBinary), and no
    // type in its tree was opted out (binaryTransferDisable). All three are memoized;
    // this stays cache-only on the bind path.
    Boolean send = binarySendCapable.get(oid);
    Boolean codec = binaryReceiveCodecCapable.get(oid);
    Boolean disabled = binaryReceiveDisabled.get(oid);
    if (send != null && codec != null && disabled != null) {
      return send && codec && !disabled;
    }
    // Built-in types resolve from the static table without a query, and their
    // recursion stays within built-ins, so computing inline is safe on the bind path.
    PgType builtin = DEFAULT_TYPES_BY_OID.get(oid);
    if (builtin == null) {
      // A user type that no result set has resolved yet: defer to text rather than
      // issue a catalog query mid-Bind. It is memoized the first time a result set
      // materializes its PgType, so a later execution uses binary.
      return false;
    }
    try {
      return backendCanSendBinary(builtin) && driverCanReceiveBinary(builtin)
          && !isBinaryReceiveDisabled(builtin);
    } catch (SQLException e) {
      return false;
    }
  }

  private boolean computeBackendCanSendBinary(PgType type) throws SQLException {
    // Arrays carry their own array_send, but it delegates to the element's send:
    // ignore the array's typsend and recurse into the element (e.g. aclitem[]).
    if (type.isArray()) {
      int elementOid = type.getTypelem();
      return elementOid == Oid.UNSPECIFIED
          ? type.hasOwnBinarySend()
          : backendCanSendBinary(getPgTypeByOid(elementOid));
    }
    // The anonymous record's field types are only known per value, not from the
    // catalog. Trust record_send optimistically; a field whose type lacks a send
    // function surfaces as a server-side error rather than being filtered here.
    if (type.getOid() == Oid.RECORD) {
      return type.hasOwnBinarySend();
    }
    // A named composite is binary-send capable only if every field type is.
    if (type.isComposite()) {
      for (PgField field : getFields(type.getOid())) {
        if (!backendCanSendBinary(getPgTypeByOid(field.getTypeOid()))) {
          return false;
        }
      }
      return true;
    }
    // A domain inherits its base type's binary-send capability.
    if (type.isDomain()) {
      int baseOid = type.getTypbasetype();
      return baseOid == Oid.UNSPECIFIED
          ? type.hasOwnBinarySend()
          : backendCanSendBinary(getPgTypeByOid(baseOid));
    }
    // Base, enum, range and multirange types: trust their own typsend. (A range's
    // subtype is now resolvable via getRangeSubtype(), but recursing into it for the
    // capability decision is deferred: range_send presence is treated optimistically.)
    return type.hasOwnBinarySend();
  }

  private boolean computeBackendCanReceiveBinary(PgType type) throws SQLException {
    // Mirrors computeBackendCanSendBinary for the send direction: a type can be sent
    // in binary only if the server has a binary input (typreceive) for it, recursing
    // into element/field/base types. A type may have typsend but not typreceive, so
    // the two directions are genuinely independent (matters for custom types).
    if (type.isArray()) {
      int elementOid = type.getTypelem();
      return elementOid == Oid.UNSPECIFIED
          ? type.hasOwnBinaryReceive()
          : backendCanReceiveBinary(getPgTypeByOid(elementOid));
    }
    // Anonymous record: field types are only known per value, so trust record_recv
    // optimistically and let a field without typreceive fail at the server.
    if (type.getOid() == Oid.RECORD) {
      return type.hasOwnBinaryReceive();
    }
    if (type.isComposite()) {
      for (PgField field : getFields(type.getOid())) {
        if (!backendCanReceiveBinary(getPgTypeByOid(field.getTypeOid()))) {
          return false;
        }
      }
      return true;
    }
    if (type.isDomain()) {
      int baseOid = type.getTypbasetype();
      return baseOid == Oid.UNSPECIFIED
          ? type.hasOwnBinaryReceive()
          : backendCanReceiveBinary(getPgTypeByOid(baseOid));
    }
    return type.hasOwnBinaryReceive();
  }

  private boolean computeDriverCanReceiveBinary(PgType type) throws SQLException {
    // Mirrors computeBackendCanSendBinary, but asks whether the driver has a binary
    // decoder rather than whether the server can send: a container's own codec
    // (ArrayCodec/CompositeCodec) is binary, so recurse into the contents.
    if (type.isArray()) {
      int elementOid = type.getTypelem();
      return elementOid == Oid.UNSPECIFIED
          ? hasOwnBinaryCodec(type)
          : driverCanReceiveBinary(getPgTypeByOid(elementOid));
    }
    // Anonymous record: CompositeCodec decodes it from the self-describing wire,
    // resolving each field's codec per value (an unknown field falls back to raw
    // bytes), so treat it as decodable, matching the backendCanSendBinary optimism.
    if (type.getOid() == Oid.RECORD) {
      return true;
    }
    if (type.isComposite()) {
      for (PgField field : getFields(type.getOid())) {
        if (!driverCanReceiveBinary(getPgTypeByOid(field.getTypeOid()))) {
          return false;
        }
      }
      return true;
    }
    if (type.isDomain()) {
      int baseOid = type.getTypbasetype();
      return baseOid == Oid.UNSPECIFIED
          ? hasOwnBinaryCodec(type)
          : driverCanReceiveBinary(getPgTypeByOid(baseOid));
    }
    return hasOwnBinaryCodec(type);
  }

  /**
   * Whether the driver can decode this exact type from binary (non-recursive).
   * Asks the resolved codec's read-side capability
   * ({@link org.postgresql.api.codec.BinaryCodec#decodesBinary()}) rather than
   * testing {@code instanceof} alone. An unmapped type resolves to {@code FallbackCodec},
   * which only wraps the raw bytes rather than reading the real binary wire, so it reports
   * no binary-read and the type is requested in text (received as {@code PGobject}); a
   * text-only codec is treated the same way. A value the server nonetheless sends in binary
   * -- e.g. an unmapped type nested in a binary {@code record} -- still decodes through
   * {@code FallbackCodec.decodeBinary} as {@code PGUnknownBinary}.
   */
  private boolean hasOwnBinaryCodec(PgType type) {
    return getCodecRegistry().canDecodeBinary(type.getOid(), type);
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

  private PreparedStatement prepareFindRangeSubtype() throws SQLException {
    PreparedStatement stmt = this.findRangeSubtype;
    if (stmt == null) {
      /* language=PostgreSQL */
      String sql = "SELECT r.rngsubtype\n"
          + "FROM pg_catalog.pg_range r\n"
          + "WHERE r.rngtypid = ?";
      stmt = conn.prepareStatement(sql);
      this.findRangeSubtype = stmt;
    }
    return stmt;
  }

  private int loadRangeSubtype(int rangeOid) throws SQLException {
    PreparedStatement stmt = prepareFindRangeSubtype();
    stmt.setInt(1, rangeOid);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) stmt).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      return Oid.UNSPECIFIED;
    }

    try (ResultSet rs = castNonNull(stmt.getResultSet())) {
      if (rs.next()) {
        return rs.getInt("rngsubtype");
      }
    }

    return Oid.UNSPECIFIED;
  }

  private PreparedStatement prepareFindMultirangeRange() throws SQLException {
    PreparedStatement stmt = this.findMultirangeRange;
    if (stmt == null) {
      /* language=PostgreSQL */
      String sql = "SELECT r.rngtypid\n"
          + "FROM pg_catalog.pg_range r\n"
          + "WHERE r.rngmultitypid = ?";
      stmt = conn.prepareStatement(sql);
      this.findMultirangeRange = stmt;
    }
    return stmt;
  }

  private int loadMultirangeRange(int multirangeOid) throws SQLException {
    PreparedStatement stmt = prepareFindMultirangeRange();
    stmt.setInt(1, multirangeOid);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) stmt).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      return Oid.UNSPECIFIED;
    }

    try (ResultSet rs = castNonNull(stmt.getResultSet())) {
      if (rs.next()) {
        return rs.getInt("rngtypid");
      }
    }

    return Oid.UNSPECIFIED;
  }
}
