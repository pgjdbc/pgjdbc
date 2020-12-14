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
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.util.GT;
import org.postgresql.util.IntBiConsumer;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TypeInfoCache implements TypeInfo {

  private static final Logger LOGGER = Logger.getLogger(TypeInfoCache.class.getName());

  private static final class TypeMaps {
    // pgname (String) -> java.sql.Types (Integer)
    final Map<String, Integer> pgNameToSQLType;

    final Map<Integer, Integer> oidToSQLType;

    // pgname (String) -> java class name (String)
    // ie "text" -> "java.lang.String"
    final Map<String, String> pgNameToJavaClass;

    // oid (Integer) -> pgname (String)
    final Map<Integer, String> oidToPgName;
    // pgname (String) -> oid (Integer)
    final Map<String, Integer> pgNameToOid;

    // type array oid -> base type's oid
    final Map<Integer, Integer> pgArrayToPgType;

    // array type oid -> base type array element delimiter
    final Map<Integer, Character> arrayOidToDelimiter;

    TypeMaps(int size) {
      final int adjustedSize = (int) ((size / .75) + 1);

      //1 entry for every type
      pgArrayToPgType = new HashMap<>(adjustedSize);

      //2 entries per type
      oidToSQLType = new HashMap<>(2 * adjustedSize);
      oidToPgName = new HashMap<>(2 * adjustedSize);
      arrayOidToDelimiter = new HashMap<>(2 * adjustedSize);

      //3 entries per type
      pgNameToSQLType = new HashMap<>(3 * adjustedSize);
      pgNameToOid = new HashMap<>(3 * adjustedSize);
      // plus the hstore entry
      pgNameToJavaClass = new HashMap<>((3 * adjustedSize) + 2);
    }
  }

  private static final TypeMaps DEFAULT_TYPES = new TypeMaps(24);

  static {
    innerAddCoreType(DEFAULT_TYPES, "int2", Oid.INT2, Types.SMALLINT, "java.lang.Integer", Oid.INT2_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "int4", Oid.INT4, Types.INTEGER, "java.lang.Integer", Oid.INT4_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "oid", Oid.OID, Types.BIGINT, "java.lang.Long", Oid.OID_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "int8", Oid.INT8, Types.BIGINT, "java.lang.Long", Oid.INT8_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "money", Oid.MONEY, Types.DOUBLE, "java.lang.Double", Oid.MONEY_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "numeric", Oid.NUMERIC, Types.NUMERIC, "java.math.BigDecimal", Oid.NUMERIC_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "float4", Oid.FLOAT4, Types.REAL, "java.lang.Float", Oid.FLOAT4_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "float8", Oid.FLOAT8, Types.DOUBLE, "java.lang.Double", Oid.FLOAT8_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "char", Oid.CHAR, Types.CHAR, "java.lang.String", Oid.CHAR_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "bpchar", Oid.BPCHAR, Types.CHAR, "java.lang.String", Oid.BPCHAR_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "varchar", Oid.VARCHAR, Types.VARCHAR, "java.lang.String", Oid.VARCHAR_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "text", Oid.TEXT, Types.VARCHAR, "java.lang.String", Oid.TEXT_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "name", Oid.NAME, Types.VARCHAR, "java.lang.String", Oid.NAME_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "bytea", Oid.BYTEA, Types.BINARY, "[B", Oid.BYTEA_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "bool", Oid.BOOL, Types.BIT, "java.lang.Boolean", Oid.BOOL_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "bit", Oid.BIT, Types.BIT, "java.lang.Boolean", Oid.BIT_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "date", Oid.DATE, Types.DATE, "java.sql.Date", Oid.DATE_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "time", Oid.TIME, Types.TIME, "java.sql.Time", Oid.TIME_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "timetz", Oid.TIMETZ, Types.TIME, "java.sql.Time", Oid.TIMETZ_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "timestamp", Oid.TIMESTAMP, Types.TIMESTAMP, "java.sql.Timestamp", Oid.TIMESTAMP_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "timestamptz", Oid.TIMESTAMPTZ, Types.TIMESTAMP, "java.sql.Timestamp",Oid.TIMESTAMPTZ_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "refcursor", Oid.REF_CURSOR, Types.REF_CURSOR, "java.sql.ResultSet", Oid.REF_CURSOR_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "json", Oid.JSON, Types.OTHER, "org.postgresql.util.PGobject", Oid.JSON_ARRAY);
    innerAddCoreType(DEFAULT_TYPES, "point", Oid.POINT, Types.OTHER, "org.postgresql.geometric.PGpoint", Oid.POINT_ARRAY);

    DEFAULT_TYPES.pgNameToJavaClass.put("hstore", Map.class.getName());
  }

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

  private final StampedLock lock = new StampedLock();
  private final TypeMaps types = new TypeMaps(4);
  // pgname (String) -> extension pgobject (Class)
  private final ConcurrentMap<String, Class<? extends PGobject>> pgNameToPgObject = new ConcurrentHashMap<>(12);
  private final BaseConnection conn;
  private final int unknownLength;
  private @Nullable PreparedStatement getOidStatementSimple;
  private @Nullable PreparedStatement getOidStatementComplexNonArray;
  private @Nullable PreparedStatement getOidStatementComplexArray;
  private @Nullable PreparedStatement getNameStatement;
  private @Nullable PreparedStatement getArrayElementOidStatement;
  private @Nullable PreparedStatement getArrayDelimiterStatement;
  private @Nullable PreparedStatement getTypeInfoStatement;
  private @Nullable PreparedStatement getAllTypeInfoStatement;

  @SuppressWarnings("method.invocation.invalid")
  public TypeInfoCache(BaseConnection conn, int unknownLength) {
    this.conn = conn;
    this.unknownLength = unknownLength;
  }

  @Override
  public void addCoreType(String pgTypeName, Integer oid, Integer sqlType,
      String javaClass, Integer arrayOid) {
    final long stamp = lock.writeLock();
    try {
      innerAddCoreType(types, pgTypeName, oid, sqlType, javaClass, arrayOid);
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * Does the work of {@link #addCoreType(String, Integer, Integer, String, Integer)}, but expecting
   * any necessary locking/synchronization to be managed externally.
   */
  private static void innerAddCoreType(TypeMaps types, String pgTypeName, Integer oid, Integer sqlType,
      String javaClass, Integer arrayOid) {
    types.pgNameToJavaClass.put(pgTypeName, javaClass);
    types.pgNameToOid.put(pgTypeName, oid);
    types.oidToPgName.put(oid, pgTypeName);
    types.pgArrayToPgType.put(arrayOid, oid);
    types.pgNameToSQLType.put(pgTypeName, sqlType);
    types.oidToSQLType.put(oid, sqlType);

    // Currently we hardcode all core types array delimiter
    // to a comma. In a stock install the only exception is
    // the box datatype and it's not a JDBC core type.
    //
    final Character delim = ',';
    types.arrayOidToDelimiter.put(oid, delim);
    types.arrayOidToDelimiter.put(arrayOid, delim);

    String pgArrayTypeName = pgTypeName + "[]";
    types.pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
    types.pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
    types.oidToSQLType.put(arrayOid, Types.ARRAY);
    types.pgNameToOid.put(pgArrayTypeName, arrayOid);
    pgArrayTypeName = "_" + pgTypeName;
    if (!DEFAULT_TYPES.pgNameToJavaClass.containsKey(pgArrayTypeName)
        && !types.pgNameToJavaClass.containsKey(pgArrayTypeName)) {
      types.pgNameToJavaClass.put(pgArrayTypeName, "java.sql.Array");
      types.pgNameToSQLType.put(pgArrayTypeName, Types.ARRAY);
      types.pgNameToOid.put(pgArrayTypeName, arrayOid);
      types.oidToPgName.put(arrayOid, pgArrayTypeName);
    }
  }

  @Override
  public void addDataType(String type, Class<? extends PGobject> klass)
      throws SQLException {
    final long stamp = lock.writeLock();
    try {
      //by setting this inside the write lock we can guarantee that any read of pgNameToPgObject (no lock required)
      //followed by a read of types.pgNameToJavaClass (protected by read lock), will find corresponding value
      pgNameToPgObject.put(type, klass);
      types.pgNameToJavaClass.put(type, klass.getName());
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addAllDataTypes(Map<String, Class<? extends PGobject>> mappings) throws SQLException {
    final long stamp = lock.writeLock();
    try {
      //by setting this inside the write lock we can guarantee that any read of pgNameToPgObject (no lock required)
      //followed by a read of types.pgNameToJavaClass (protected by read lock), will find corresponding value
      pgNameToPgObject.putAll(mappings);
      mappings.forEach((type, klass) -> {
        types.pgNameToJavaClass.put(type, klass.getName());
      });
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  @Override
  public Iterator<String> getPGTypeNamesWithSQLTypes() {
    Collection<String> names = DEFAULT_TYPES.pgNameToSQLType.keySet();

    final long stamp = lock.readLock();
    try {
      if (!types.pgNameToSQLType.isEmpty()) {
        //make defensive copy of the key set so that returned iterator is safe even in face
        //of concurrent modification to this class
        final List<String> defensiveCopy = new ArrayList<>(names.size() + types.pgNameToSQLType.size());
        defensiveCopy.addAll(names);
        defensiveCopy.addAll(types.pgNameToSQLType.keySet());
        names = defensiveCopy;
      }
    } finally {
      lock.unlockRead(stamp);
    }
    //return unmodifiable copy so that consumers do not think they are removing anything
    //from this cache
    return Collections.unmodifiableCollection(names).iterator();
  }

  @Override
  public Iterator<Integer> getPGTypeOidsWithSQLTypes() {
    Collection<Integer> oids = DEFAULT_TYPES.oidToSQLType.keySet();
    final long stamp = lock.readLock();
    try {
      if (!types.oidToSQLType.isEmpty()) {
        //make defensive copy of the key set so that returned iterator is safe even in face
        //of concurrent modification to this class
        final List<Integer> defensiveCopy = new ArrayList<>(oids.size() + types.oidToSQLType.size());
        defensiveCopy.addAll(oids);
        defensiveCopy.addAll(types.oidToSQLType.keySet());
      }
    } finally {
      lock.unlockRead(stamp);
    }
    //return unmodifiable copy so that consumers do not think they are removing anything
    //from this cache
    return Collections.unmodifiableCollection(oids).iterator();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void forEachSQLOidType(IntBiConsumer action) {
    final BiConsumer<? super Integer, ? super Integer> mapAction = action::accept;
    DEFAULT_TYPES.oidToSQLType.forEach(mapAction);
    final long stamp = lock.readLock();
    try {
      types.oidToSQLType.forEach(mapAction);
    } finally {
      lock.unlockRead(stamp);
    }
  }

  private String getSQLTypeQuery(boolean typoidParam) {
    // There's no great way of telling what's an array type.
    // People can name their own types starting with _.
    // Other types use typelem that aren't actually arrays, like box.
    //
    // in case of multiple records (in different schemas) choose the one from the current
    // schema,
    // otherwise take the last version of a type that is at least more deterministic then before
    // (keeping old behaviour of finding types, that should not be found without correct search
    // path)
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT typinput='array_in'::regproc as is_array, typtype, typname, pg_type.oid ");
    sql.append("  FROM pg_catalog.pg_type ");
    sql.append("  LEFT JOIN (select ns.oid as nspoid, ns.nspname, r.r ");
    sql.append("          from pg_namespace as ns ");
    // -- go with older way of unnesting array to be compatible with 8.0
    sql.append("          join ( select s.r, (current_schemas(false))[s.r] as nspname ");
    sql.append("                   from generate_series(1, array_upper(current_schemas(false), 1)) as s(r) ) as r ");
    sql.append("         using ( nspname ) ");
    sql.append("       ) as sp ");
    sql.append("    ON sp.nspoid = typnamespace ");
    if (typoidParam) {
      sql.append(" WHERE pg_type.oid = ? ");
    }
    sql.append(" ORDER BY sp.r, pg_type.oid DESC;");
    return sql.toString();
  }

  private int getSQLTypeFromQueryResult(ResultSet rs) throws SQLException {
    Integer type = null;
    boolean isArray = rs.getBoolean("is_array");
    String typtype = rs.getString("typtype");
    if (isArray) {
      type = Types.ARRAY;
    } else if ("c".equals(typtype)) {
      type = Types.STRUCT;
    } else if ("d".equals(typtype)) {
      type = Types.DISTINCT;
    } else if ("e".equals(typtype)) {
      type = Types.VARCHAR;
    }
    if (type == null) {
      type = Types.OTHER;
    }
    return type;
  }

  private PreparedStatement prepareGetAllTypeInfoStatement() throws SQLException {
    PreparedStatement getAllTypeInfoStatement = this.getAllTypeInfoStatement;
    if (getAllTypeInfoStatement == null) {
      getAllTypeInfoStatement = conn.prepareStatement(getSQLTypeQuery(false));
      this.getAllTypeInfoStatement = getAllTypeInfoStatement;
    }
    return getAllTypeInfoStatement;
  }

  public void cacheSQLTypes() throws SQLException {
    LOGGER.log(Level.FINEST, "caching all SQL typecodes");
    PreparedStatement getAllTypeInfoStatement = prepareGetAllTypeInfoStatement();
    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) getAllTypeInfoStatement)
        .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }
    ResultSet rs = castNonNull(getAllTypeInfoStatement.getResultSet());
    // it is safe to hold write lock while interacting with result set because all types
    // come from the default mappings, which do not obtain a read lock to access
    final long stamp = lock.writeLock();
    try {
      while (rs.next()) {
        final String typeName = castNonNull(rs.getString("typname"));
        final Integer type = getSQLTypeFromQueryResult(rs);
        final Integer typeOid = castNonNull(rs.getInt("oid"));
        if (!DEFAULT_TYPES.pgNameToSQLType.containsKey(typeName)) {
          types.pgNameToSQLType.putIfAbsent(typeName, type);
        }
        if (!DEFAULT_TYPES.oidToSQLType.containsKey(typeOid)) {
          types.oidToSQLType.putIfAbsent(typeOid, type);
        }
      }
    } finally {
      lock.unlockWrite(stamp);
    }
    rs.close();
  }

  private PreparedStatement prepareGetTypeInfoStatement() throws SQLException {
    PreparedStatement getTypeInfoStatement = this.getTypeInfoStatement;
    if (getTypeInfoStatement == null) {
      getTypeInfoStatement = conn.prepareStatement(getSQLTypeQuery(true));
      this.getTypeInfoStatement = getTypeInfoStatement;
    }
    return getTypeInfoStatement;
  }

  public int getSQLType(String pgTypeName) throws SQLException {
    return getSQLType(castNonNull(getPGType(pgTypeName)));
  }

  public int getSQLType(int typeOid) throws SQLException {
    if (typeOid == Oid.UNSPECIFIED) {
      return Types.OTHER;
    }

    final Integer key = typeOid;

    Integer i = DEFAULT_TYPES.oidToSQLType.get(key);
    if (i != null) {
      return i;
    }
    long stamp = lock.readLock();
    try {
      i = types.oidToSQLType.get(key);
    } finally {
      lock.unlockRead(stamp);
    }
    if (i != null) {
      return i;
    }

    LOGGER.log(Level.FINEST, "querying SQL typecode for pg type oid '{0}'", key);

    PreparedStatement getTypeInfoStatement = prepareGetTypeInfoStatement();

    getTypeInfoStatement.setInt(1, typeOid);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) getTypeInfoStatement)
        .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    ResultSet rs = castNonNull(getTypeInfoStatement.getResultSet());

    int sqlType = Types.OTHER;
    if (rs.next()) {
      sqlType = getSQLTypeFromQueryResult(rs);
    }
    rs.close();

    stamp = lock.writeLock();
    try {
      types.oidToSQLType.put(key, sqlType);
    } finally {
      lock.unlockWrite(stamp);
    }
    return sqlType;
  }

  private PreparedStatement getOidStatement(String pgTypeName) throws SQLException {
    boolean isArray = pgTypeName.endsWith("[]");
    boolean hasQuote = pgTypeName.contains("\"");
    int dotIndex = pgTypeName.indexOf('.');

    if (dotIndex == -1 && !hasQuote && !isArray) {
      if (getOidStatementSimple == null) {
        String sql;
        // see comments in @getSQLType()
        // -- go with older way of unnesting array to be compatible with 8.0
        sql = "SELECT pg_type.oid, typname "
              + "  FROM pg_catalog.pg_type "
              + "  LEFT "
              + "  JOIN (select ns.oid as nspoid, ns.nspname, r.r "
              + "          from pg_namespace as ns "
              + "          join ( select s.r, (current_schemas(false))[s.r] as nspname "
              + "                   from generate_series(1, array_upper(current_schemas(false), 1)) as s(r) ) as r "
              + "         using ( nspname ) "
              + "       ) as sp "
              + "    ON sp.nspoid = typnamespace "
              + " WHERE typname = ? "
              + " ORDER BY sp.r, pg_type.oid DESC LIMIT 1;";
        getOidStatementSimple = conn.prepareStatement(sql);
      }
      // coerce to lower case to handle upper case type names
      String lcName = pgTypeName.toLowerCase();
      // default arrays are represented with _ as prefix ... this dont even work for public schema
      // fully
      getOidStatementSimple.setString(1, lcName);
      return getOidStatementSimple;
    }
    PreparedStatement oidStatementComplex;
    if (isArray) {
      if (getOidStatementComplexArray == null) {
        String sql;
        if (conn.haveMinimumServerVersion(ServerVersion.v8_3)) {
          sql = "SELECT t.typarray, arr.typname "
              + "  FROM pg_catalog.pg_type t"
              + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
              + "  JOIN pg_catalog.pg_type arr ON arr.oid = t.typarray"
              + " WHERE t.typname = ? AND (n.nspname = ? OR ? AND n.nspname = ANY (current_schemas(true)))"
              + " ORDER BY t.oid DESC LIMIT 1";
        } else {
          sql = "SELECT t.oid, t.typname "
              + "  FROM pg_catalog.pg_type t"
              + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
              + " WHERE t.typelem = (SELECT oid FROM pg_catalog.pg_type WHERE typname = ?)"
              + " AND substring(t.typname, 1, 1) = '_' AND t.typlen = -1"
              + " AND (n.nspname = ? OR ? AND n.nspname = ANY (current_schemas(true)))"
              + " ORDER BY t.typelem DESC LIMIT 1";
        }
        getOidStatementComplexArray = conn.prepareStatement(sql);
      }
      oidStatementComplex = getOidStatementComplexArray;
    } else {
      if (getOidStatementComplexNonArray == null) {
        String sql = "SELECT t.oid, t.typname "
            + "  FROM pg_catalog.pg_type t"
            + "  JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
            + " WHERE t.typname = ? AND (n.nspname = ? OR ? AND n.nspname = ANY (current_schemas(true)))"
            + " ORDER BY t.oid DESC LIMIT 1";
        getOidStatementComplexNonArray = conn.prepareStatement(sql);
      }
      oidStatementComplex = getOidStatementComplexNonArray;
    }
    //type name requested may be schema specific, of the form "{schema}"."typeName",
    //or may check across all schemas where a schema is not specified.
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
      schema = schema.toLowerCase();
    }
    if (name.startsWith("\"") && name.endsWith("\"")) {
      name = name.substring(1, name.length() - 1);
    } else {
      name = name.toLowerCase();
    }
    oidStatementComplex.setString(1, name);
    oidStatementComplex.setString(2, schema);
    oidStatementComplex.setBoolean(3, schema == null);
    return oidStatementComplex;
  }

  public int getPGType(String pgTypeName) throws SQLException {
    Integer oid = DEFAULT_TYPES.pgNameToOid.get(pgTypeName);
    if (oid != null) {
      return oid;
    }
    long stamp = lock.readLock();
    try {
      oid = types.pgNameToOid.get(pgTypeName);
    } finally {
      lock.unlockRead(stamp);
    }
    if (oid != null) {
      return oid;
    }

    PreparedStatement oidStatement = getOidStatement(pgTypeName);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) oidStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    oid = Oid.UNSPECIFIED;
    ResultSet rs = castNonNull(oidStatement.getResultSet());
    // it is safe to hold write lock while interacting with result set because all types
    // come from the default mappings, which do not obtain a read lock to access
    stamp = lock.writeLock();
    try {
      if (rs.next()) {
        oid = (int) rs.getLong(1);
        String internalName = castNonNull(rs.getString(2));
        types.oidToPgName.put(oid, internalName);
        types.pgNameToOid.put(internalName, oid);
      }
      types.pgNameToOid.put(pgTypeName, oid);
    } finally {
      lock.unlockWrite(stamp);
    }
    rs.close();

    return oid;
  }

  public @Nullable String getPGType(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      // TODO: it would be great to forbid UNSPECIFIED argument, and make the return type non-nullable
      return null;
    }

    final Integer key = oid;

    String pgTypeName = DEFAULT_TYPES.oidToPgName.get(key);
    if (pgTypeName != null) {
      return pgTypeName;
    }
    long stamp = lock.readLock();
    try {
      pgTypeName = types.oidToPgName.get(key);
    } finally {
      lock.unlockRead(stamp);
    }
    if (pgTypeName != null) {
      return pgTypeName;
    }

    PreparedStatement getNameStatement = prepareGetNameStatement();

    getNameStatement.setInt(1, oid);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) getNameStatement).executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    ResultSet rs = castNonNull(getNameStatement.getResultSet());
    if (rs.next()) {
      boolean onPath = rs.getBoolean(1);
      String schema = castNonNull(rs.getString(2), "schema");
      String name = castNonNull(rs.getString(3), "name");
      stamp = lock.writeLock();
      try {
        if (onPath) {
          pgTypeName = name;
          types.pgNameToOid.put(schema + "." + name, key);
        } else {
          // TODO: escaping !?
          pgTypeName = "\"" + schema + "\".\"" + name + "\"";
          // if all is lowercase add special type info
          // TODO: should probably check for all special chars
          if (schema.equals(schema.toLowerCase()) && schema.indexOf('.') == -1
              && name.equals(name.toLowerCase()) && name.indexOf('.') == -1) {
            types.pgNameToOid.put(schema + "." + name, key);
          }
        }
        types.pgNameToOid.put(pgTypeName, key);
        types.oidToPgName.put(key, pgTypeName);
      } finally {
        lock.unlockWrite(stamp);
      }
    }
    rs.close();

    return pgTypeName;
  }

  private PreparedStatement prepareGetNameStatement() throws SQLException {
    PreparedStatement getNameStatement = this.getNameStatement;
    if (getNameStatement == null) {
      String sql;
      sql = "SELECT n.nspname = ANY(current_schemas(true)), n.nspname, t.typname "
            + "FROM pg_catalog.pg_type t "
            + "JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid WHERE t.oid = ?";

      this.getNameStatement = getNameStatement = conn.prepareStatement(sql);
    }
    return getNameStatement;
  }

  public int getPGArrayType(String elementTypeName) throws SQLException {
    elementTypeName = getTypeForAlias(elementTypeName);
    return getPGType(elementTypeName + "[]");
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
  protected int convertArrayToBaseOid(int oid) {
    final Integer key = oid;
    Integer i = DEFAULT_TYPES.pgArrayToPgType.get(key);
    if (i != null) {
      return i;
    }
    long stamp = lock.readLock();
    try {
      i = types.pgArrayToPgType.get(key);
    } finally {
      lock.unlockRead(stamp);
    }
    return i != null ? i : oid;
  }

  public char getArrayDelimiter(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return ',';
    }

    final Integer key = oid;

    Character delim = DEFAULT_TYPES.arrayOidToDelimiter.get(key);
    if (delim != null) {
      return delim;
    }
    long stamp = lock.readLock();
    try {
      delim = types.arrayOidToDelimiter.get(key);
    } finally {
      lock.unlockRead(stamp);
    }
    if (delim != null) {
      return delim;
    }

    PreparedStatement getArrayDelimiterStatement = prepareGetArrayDelimiterStatement();

    getArrayDelimiterStatement.setInt(1, oid);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) getArrayDelimiterStatement)
        .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    ResultSet rs = castNonNull(getArrayDelimiterStatement.getResultSet());
    if (!rs.next()) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    String s = castNonNull(rs.getString(1));
    delim = s.charAt(0);

    stamp = lock.writeLock();
    try {
      types.arrayOidToDelimiter.put(key, delim);
    } finally {
      lock.unlockWrite(stamp);
    }

    rs.close();

    return delim;
  }

  private PreparedStatement prepareGetArrayDelimiterStatement() throws SQLException {
    PreparedStatement getArrayDelimiterStatement = this.getArrayDelimiterStatement;
    if (getArrayDelimiterStatement == null) {
      String sql;
      sql = "SELECT e.typdelim FROM pg_catalog.pg_type t, pg_catalog.pg_type e "
            + "WHERE t.oid = ? and t.typelem = e.oid";
      this.getArrayDelimiterStatement = getArrayDelimiterStatement = conn.prepareStatement(sql);
    }
    return getArrayDelimiterStatement;
  }

  public int getPGArrayElement(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return Oid.UNSPECIFIED;
    }

    final Integer key = oid;

    Integer pgType = DEFAULT_TYPES.pgArrayToPgType.get(key);
    if (pgType != null) {
      return pgType;
    }
    long stamp = lock.readLock();
    try {
      pgType = types.pgArrayToPgType.get(key);
    } finally {
      lock.unlockRead(stamp);
    }

    if (pgType != null) {
      return pgType;
    }

    PreparedStatement getArrayElementOidStatement = prepareGetArrayElementOidStatement();

    getArrayElementOidStatement.setInt(1, oid);

    // Go through BaseStatement to avoid transaction start.
    if (!((BaseStatement) getArrayElementOidStatement)
        .executeWithFlags(QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    ResultSet rs = castNonNull(getArrayElementOidStatement.getResultSet());
    if (!rs.next()) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    pgType = (int) rs.getLong(1);
    boolean onPath = rs.getBoolean(2);
    String schema = rs.getString(3);
    String name = castNonNull(rs.getString(4));
    stamp = lock.writeLock();
    try {
      types.pgArrayToPgType.put(key, pgType);
      types.pgNameToOid.put(schema + "." + name, pgType);
      String fullName = "\"" + schema + "\".\"" + name + "\"";
      types.pgNameToOid.put(fullName, pgType);
      if (onPath && name.equals(name.toLowerCase())) {
        types.oidToPgName.put(pgType, name);
        types.pgNameToOid.put(name, pgType);
      } else {
        types.oidToPgName.put(pgType, fullName);
      }
    } finally {
      lock.unlockWrite(stamp);
    }

    rs.close();

    return pgType;
  }

  private PreparedStatement prepareGetArrayElementOidStatement() throws SQLException {
    PreparedStatement getArrayElementOidStatement = this.getArrayElementOidStatement;
    if (getArrayElementOidStatement == null) {
      String sql;
      sql = "SELECT e.oid, n.nspname = ANY(current_schemas(true)), n.nspname, e.typname "
            + "FROM pg_catalog.pg_type t JOIN pg_catalog.pg_type e ON t.typelem = e.oid "
            + "JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid WHERE t.oid = ?";
      this.getArrayElementOidStatement = getArrayElementOidStatement = conn.prepareStatement(sql);
    }
    return getArrayElementOidStatement;
  }

  public @Nullable Class<? extends PGobject> getPGobject(String type) {
    return pgNameToPgObject.get(type);
  }

  public String getJavaClass(int oid) throws SQLException {
    String pgTypeName = getPGType(oid);
    if (pgTypeName == null) {
      // Technically speaking, we should not be here
      // null result probably means oid == UNSPECIFIED which has no clear way
      // to map to Java
      return "java.lang.String";
    }

    String result = DEFAULT_TYPES.pgNameToJavaClass.get(pgTypeName);
    if (result != null) {
      return result;
    }
    long stamp = lock.readLock();
    try {
      result = types.pgNameToJavaClass.get(pgTypeName);
    } finally {
      lock.unlockRead(stamp);
    }
    if (result != null) {
      return result;
    }

    if (getSQLType(pgTypeName) == Types.ARRAY) {
      result = "java.sql.Array";
      stamp = lock.writeLock();
      try {
        types.pgNameToJavaClass.put(pgTypeName, result);
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    return result == null ? "java.lang.String" : result;
  }

  public String getTypeForAlias(String alias) {
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
}
