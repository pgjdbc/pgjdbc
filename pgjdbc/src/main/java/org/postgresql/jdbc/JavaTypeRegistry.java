/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.Experimental;
import org.postgresql.core.Oid;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.JDBCType;
import java.sql.SQLType;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for Java ↔ PostgreSQL type mappings.
 * This class is responsible for:
 * <ul>
 *   <li>Mapping Java classes to PostgreSQL array OIDs (for setObject with arrays)</li>
 *   <li>Mapping PostgreSQL type names to custom PGobject subclasses</li>
 *   <li>Providing default Java class names for PostgreSQL types</li>
 * </ul>
 *
 * <p>This class is connection-scoped and maintains per-connection type mappings.</p>
 *
 * @since 42.8.0
 */
@Experimental("Java type registry API is experimental and may change in future releases")
public class JavaTypeRegistry {

  // Java class -> array OID mapping (connection-scoped)
  private final Map<Class<?>, Integer> javaClassToArrayOid = new ConcurrentHashMap<>();

  // PostgreSQL type name -> PGobject subclass mapping (connection-scoped)
  private final Map<String, Class<? extends PGobject>> pgNameToPgObject = new ConcurrentHashMap<>();

  /**
   * Creates a new JavaTypeRegistry with default mappings for built-in types.
   */
  @SuppressWarnings({"this-escape", "method.invocation"})
  public JavaTypeRegistry() {
    initializeDefaultMappings();
  }

  /**
   * Initializes default Java class to array OID mappings for built-in types.
   */
  private void initializeDefaultMappings() {
    // Numeric types
    javaClassToArrayOid.put(Short.class, Oid.INT2_ARRAY);
    javaClassToArrayOid.put(Integer.class, Oid.INT4_ARRAY);
    javaClassToArrayOid.put(Long.class, Oid.INT8_ARRAY);
    javaClassToArrayOid.put(Float.class, Oid.FLOAT4_ARRAY);
    javaClassToArrayOid.put(Double.class, Oid.FLOAT8_ARRAY);
    javaClassToArrayOid.put(BigDecimal.class, Oid.NUMERIC_ARRAY);

    // String types
    javaClassToArrayOid.put(String.class, Oid.VARCHAR_ARRAY);

    // Boolean
    javaClassToArrayOid.put(Boolean.class, Oid.BOOL_ARRAY);

    // Binary
    javaClassToArrayOid.put(byte[].class, Oid.BYTEA_ARRAY);

    // Date/Time
    javaClassToArrayOid.put(java.sql.Date.class, Oid.DATE_ARRAY);
    javaClassToArrayOid.put(java.sql.Time.class, Oid.TIME_ARRAY);
    javaClassToArrayOid.put(java.sql.Timestamp.class, Oid.TIMESTAMP_ARRAY);

    // Special types
    javaClassToArrayOid.put(java.util.UUID.class, Oid.UUID_ARRAY);

    // PostgreSQL type name → PGobject subclass mappings for built-in types.
    // These correspond to the legacy BASE_TYPES mappings in the old TypeInfoCache
    // and are required for connection.getObject(typeName, ...) and
    // ResultSet.getObject(int, PGobject.class) to return the right subclass.
    pgNameToPgObject.put("box", org.postgresql.geometric.PGbox.class);
    pgNameToPgObject.put("circle", org.postgresql.geometric.PGcircle.class);
    pgNameToPgObject.put("line", org.postgresql.geometric.PGline.class);
    pgNameToPgObject.put("lseg", org.postgresql.geometric.PGlseg.class);
    pgNameToPgObject.put("path", org.postgresql.geometric.PGpath.class);
    pgNameToPgObject.put("point", org.postgresql.geometric.PGpoint.class);
    pgNameToPgObject.put("polygon", org.postgresql.geometric.PGpolygon.class);
    pgNameToPgObject.put("money", org.postgresql.util.PGmoney.class);
    pgNameToPgObject.put("interval", org.postgresql.util.PGInterval.class);
  }

  /**
   * Registers a mapping from a Java class to a PostgreSQL array OID.
   * This is used when creating arrays from Java arrays.
   *
   * @param javaClass the Java class
   * @param arrayOid the PostgreSQL array type OID
   */
  public void registerArrayOid(Class<?> javaClass, int arrayOid) {
    if (arrayOid != Oid.UNSPECIFIED) {
      javaClassToArrayOid.putIfAbsent(javaClass, arrayOid);
    }
  }

  /**
   * Gets the PostgreSQL array OID for a given Java class.
   *
   * @param javaClass the Java class
   * @return the array OID, or {@link Oid#UNSPECIFIED} if not found
   */
  public int getArrayOidForJavaClass(Class<?> javaClass) {
    Integer oid = javaClassToArrayOid.get(javaClass);
    return oid != null ? oid : Oid.UNSPECIFIED;
  }

  /**
   * Registers a custom PGobject subclass for a PostgreSQL type name.
   *
   * @param pgTypeName the PostgreSQL type name
   * @param klass the PGobject subclass
   */
  public void addPGobject(String pgTypeName, Class<? extends PGobject> klass) {
    pgNameToPgObject.put(pgTypeName, klass);
  }

  /**
   * Gets the PGobject subclass registered for a PostgreSQL type name.
   *
   * @param pgTypeName the PostgreSQL type name
   * @return the PGobject subclass, or null if not registered
   */
  public @Nullable Class<? extends PGobject> getPGobject(String pgTypeName) {
    return pgNameToPgObject.get(pgTypeName);
  }

  /**
   * Returns the default Java class name for a given PostgreSQL OID.
   * This is used by ResultSetMetaData.getColumnClassName().
   *
   * @param oid the PostgreSQL type OID
   * @return the fully qualified Java class name
   */
  public static String getDefaultJavaClassName(int oid) {
    Class<?> clazz = getDefaultJavaClass(oid);
    return clazz.getName();
  }

  /**
   * Returns the default Java class for a given PostgreSQL OID.
   *
   * @param oid the PostgreSQL type OID
   * @return the default Java class for this type
   */
  public static Class<?> getDefaultJavaClass(int oid) {
    switch (oid) {
      // Numeric types
      case Oid.INT2:
        return Integer.class;
      case Oid.INT4:
        return Integer.class;
      case Oid.INT8:
        return Long.class;
      case Oid.OID:
        return Long.class;
      case Oid.FLOAT4:
        return Float.class;
      case Oid.FLOAT8:
        return Double.class;
      case Oid.NUMERIC:
        return BigDecimal.class;
      case Oid.MONEY:
        return org.postgresql.util.PGmoney.class;

      // Boolean
      case Oid.BOOL:
        return Boolean.class;

      // Bit strings
      case Oid.BIT:
      case Oid.VARBIT:
        return Boolean.class;

      // String types
      case Oid.VARCHAR:
      case Oid.TEXT:
      case Oid.BPCHAR:
      case Oid.NAME:
        return String.class;

      // Binary
      case Oid.BYTEA:
        return byte[].class;

      // Date/Time
      case Oid.DATE:
        return java.sql.Date.class;
      case Oid.TIME:
      case Oid.TIMETZ:
        return java.sql.Time.class;
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        return java.sql.Timestamp.class;
      case Oid.INTERVAL:
        return org.postgresql.util.PGInterval.class;

      // Geometric types
      case Oid.POINT:
        return org.postgresql.geometric.PGpoint.class;
      case Oid.BOX:
        return org.postgresql.geometric.PGbox.class;
      case Oid.CIRCLE:
        return org.postgresql.geometric.PGcircle.class;
      case Oid.LINE:
        return org.postgresql.geometric.PGline.class;
      case Oid.LSEG:
        return org.postgresql.geometric.PGlseg.class;
      case Oid.PATH:
        return org.postgresql.geometric.PGpath.class;
      case Oid.POLYGON:
        return org.postgresql.geometric.PGpolygon.class;

      // Special types
      case Oid.UUID:
        return java.util.UUID.class;
      case Oid.XML:
        return java.sql.SQLXML.class;
      case Oid.JSON:
      case Oid.JSONB:
        return org.postgresql.util.PGobject.class;
      case Oid.REFCURSOR:
        return java.sql.ResultSet.class;
      case Oid.HSTORE:
        return java.util.Map.class;

      // Array types
      case Oid.INT2_ARRAY:
      case Oid.INT4_ARRAY:
      case Oid.INT8_ARRAY:
      case Oid.OID_ARRAY:
      case Oid.FLOAT4_ARRAY:
      case Oid.FLOAT8_ARRAY:
      case Oid.NUMERIC_ARRAY:
      case Oid.BOOL_ARRAY:
      case Oid.VARCHAR_ARRAY:
      case Oid.TEXT_ARRAY:
      case Oid.BPCHAR_ARRAY:
      case Oid.BYTEA_ARRAY:
      case Oid.DATE_ARRAY:
      case Oid.TIME_ARRAY:
      case Oid.TIMETZ_ARRAY:
      case Oid.TIMESTAMP_ARRAY:
      case Oid.TIMESTAMPTZ_ARRAY:
      case Oid.UUID_ARRAY:
        return Array.class;

      default:
        return String.class;
    }
  }

  /**
   * Extracts the JDBC type code from an SQLType.
   *
   * <p>This method handles:
   * <ul>
   *   <li>{@link JDBCType} - returns the vendor type number directly</li>
   *   <li>PostgreSQL type names - maps common names to Types.* constants</li>
   *   <li>Unknown types - returns {@link Types#OTHER}</li>
   * </ul>
   *
   * @param sqlType the SQL type
   * @return the JDBC type code (from {@link Types})
   */
  public static int getSqlTypeCode(SQLType sqlType) {
    if (sqlType instanceof JDBCType) {
      return ((JDBCType) sqlType).getVendorTypeNumber();
    }

    // Check if it's a PostgreSQL-specific SQLType by name
    String typeName = sqlType.getName();

    // Map common PostgreSQL type names to JDBC types
    switch (typeName.toLowerCase(Locale.ROOT)) {
      case "int2":
      case "smallint":
        return Types.SMALLINT;
      case "int4":
      case "integer":
      case "serial":
        return Types.INTEGER;
      case "int8":
      case "bigint":
      case "bigserial":
        return Types.BIGINT;
      case "float4":
      case "real":
        return Types.REAL;
      case "float8":
      case "double precision":
        return Types.DOUBLE;
      case "numeric":
      case "decimal":
        return Types.NUMERIC;
      case "varchar":
      case "character varying":
      case "text":
        return Types.VARCHAR;
      case "bpchar":
      case "char":
      case "character":
        return Types.CHAR;
      case "bool":
      case "boolean":
        return Types.BOOLEAN;
      case "date":
        return Types.DATE;
      case "time":
      case "time without time zone":
        return Types.TIME;
      case "timetz":
      case "time with time zone":
        return Types.TIME_WITH_TIMEZONE;
      case "timestamp":
      case "timestamp without time zone":
        return Types.TIMESTAMP;
      case "timestamptz":
      case "timestamp with time zone":
        return Types.TIMESTAMP_WITH_TIMEZONE;
      case "bytea":
        return Types.BINARY;
      case "bit":
        return Types.BIT;
      case "varbit":
      case "bit varying":
        return Types.OTHER;
      case "uuid":
      case "json":
      case "jsonb":
      case "hstore":
        return Types.OTHER;
      case "xml":
        return Types.SQLXML;
      case "refcursor":
        return Types.REF_CURSOR;
      default:
        // For composite types and other PostgreSQL-specific types, use OTHER
        return Types.OTHER;
    }
  }

  /**
   * Returns the PostgreSQL OID for a given SQLType.
   *
   * <p>This method handles:
   * <ul>
   *   <li>{@link JDBCType} (java.sql vendor) - maps to corresponding PostgreSQL OID</li>
   *   <li>PGSQLType (org.postgresql vendor) - returns the vendor type number directly</li>
   *   <li>Custom SQLType implementations - maps by type name</li>
   * </ul>
   *
   * @param sqlType the SQL type
   * @param value optional value for type inference (e.g., array element type)
   * @return the PostgreSQL OID, or {@link Oid#UNSPECIFIED} if unknown
   */
  public int getOidForSQLType(SQLType sqlType, @Nullable Object value) {
    String vendor = sqlType.getVendor();

    if ("java.sql".equals(vendor)) {
      // JDBCType - map from JDBC type code
      Integer typeNumber = sqlType.getVendorTypeNumber();
      if (typeNumber != null) {
        return getOidForSqlType(typeNumber, value);
      }
      return Oid.UNSPECIFIED;
    }

    if ("org.postgresql".equals(vendor)) {
      // PGSQLType - vendor type number is the OID
      Integer oid = sqlType.getVendorTypeNumber();
      return oid != null ? oid : Oid.UNSPECIFIED;
    }

    // Custom SQLType - try to map by name
    return getOidByTypeName(sqlType.getName());
  }

  /**
   * Returns the PostgreSQL OID for a given JDBC type code.
   *
   * @param sqlType the JDBC type code (from {@link Types})
   * @param value optional value for type inference
   * @return the PostgreSQL OID, or {@link Oid#UNSPECIFIED} if unknown
   */
  public static int getOidForSqlType(int sqlType, @Nullable Object value) {
    switch (sqlType) {
      case Types.BIT:
      case Types.BOOLEAN:
        return Oid.BOOL;
      case Types.TINYINT:
      case Types.SMALLINT:
        return Oid.INT2;
      case Types.INTEGER:
        return Oid.INT4;
      case Types.BIGINT:
        return Oid.INT8;
      case Types.REAL:
        return Oid.FLOAT4;
      case Types.FLOAT:
      case Types.DOUBLE:
        return Oid.FLOAT8;
      case Types.NUMERIC:
      case Types.DECIMAL:
        return Oid.NUMERIC;
      case Types.CHAR:
        return Oid.BPCHAR;
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        return Oid.VARCHAR;
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        return Oid.BYTEA;
      case Types.DATE:
        return Oid.DATE;
      case Types.TIME:
        return Oid.TIME;
      case Types.TIME_WITH_TIMEZONE:
        return Oid.TIMETZ;
      case Types.TIMESTAMP:
        return Oid.TIMESTAMP;
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return Oid.TIMESTAMPTZ;
      case Types.ARRAY:
        // For arrays, we need the element type from the value
        return Oid.UNSPECIFIED;
      case Types.SQLXML:
        return Oid.XML;
      case Types.REF_CURSOR:
        return Oid.REFCURSOR;
      case Types.BLOB:
      case Types.CLOB:
        return Oid.OID;
      default:
        return Oid.UNSPECIFIED;
    }
  }

  /**
   * Returns the PostgreSQL OID for a setNull() operation.
   * This method has special handling for certain types where NULL binding
   * requires UNSPECIFIED to let the server infer the type.
   *
   * @param sqlType the JDBC type code (from {@link Types})
   * @param stringVarcharFlag whether to use VARCHAR for string types
   * @return the PostgreSQL OID, or {@link Oid#UNSPECIFIED} if type should be inferred
   * @throws IllegalArgumentException if the sqlType is not recognized
   */
  public static int getOidForSetNull(int sqlType, boolean stringVarcharFlag) {
    switch (sqlType) {
      case Types.BIT:
      case Types.BOOLEAN:
        return Oid.BOOL;
      case Types.TINYINT:
      case Types.SMALLINT:
        return Oid.INT2;
      case Types.INTEGER:
        return Oid.INT4;
      case Types.BIGINT:
        return Oid.INT8;
      case Types.REAL:
        return Oid.FLOAT4;
      case Types.FLOAT:
      case Types.DOUBLE:
        return Oid.FLOAT8;
      case Types.NUMERIC:
      case Types.DECIMAL:
        return Oid.NUMERIC;
      case Types.CHAR:
        return Oid.BPCHAR;
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        return stringVarcharFlag ? Oid.VARCHAR : Oid.UNSPECIFIED;
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        return Oid.BYTEA;
      case Types.DATE:
        return Oid.DATE;
      case Types.TIME:
      case Types.TIME_WITH_TIMEZONE:
      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE:
        // Use UNSPECIFIED for time types to let server infer correct type
        return Oid.UNSPECIFIED;
      case Types.SQLXML:
        return Oid.XML;
      case Types.REF_CURSOR:
        return Oid.REFCURSOR;
      case Types.BLOB:
      case Types.CLOB:
        return Oid.OID;
      case Types.ARRAY:
      case Types.DISTINCT:
      case Types.STRUCT:
      case Types.NULL:
      case Types.OTHER:
        return Oid.UNSPECIFIED;
      default:
        // Signal unknown type - caller should handle
        return -1;
    }
  }

  /**
   * Returns the PostgreSQL OID for a given type name.
   *
   * @param typeName the PostgreSQL type name
   * @return the PostgreSQL OID, or {@link Oid#UNSPECIFIED} if unknown
   */
  public static int getOidByTypeName(String typeName) {
    switch (typeName.toLowerCase(Locale.ROOT)) {
      case "int2":
      case "smallint":
        return Oid.INT2;
      case "int4":
      case "integer":
      case "serial":
        return Oid.INT4;
      case "int8":
      case "bigint":
      case "bigserial":
        return Oid.INT8;
      case "float4":
      case "real":
        return Oid.FLOAT4;
      case "float8":
      case "double precision":
        return Oid.FLOAT8;
      case "numeric":
      case "decimal":
        return Oid.NUMERIC;
      case "varchar":
      case "character varying":
        return Oid.VARCHAR;
      case "text":
        return Oid.TEXT;
      case "bpchar":
      case "char":
      case "character":
        return Oid.BPCHAR;
      case "bool":
      case "boolean":
        return Oid.BOOL;
      case "bytea":
        return Oid.BYTEA;
      case "date":
        return Oid.DATE;
      case "time":
      case "time without time zone":
        return Oid.TIME;
      case "timetz":
      case "time with time zone":
        return Oid.TIMETZ;
      case "timestamp":
      case "timestamp without time zone":
        return Oid.TIMESTAMP;
      case "timestamptz":
      case "timestamp with time zone":
        return Oid.TIMESTAMPTZ;
      case "uuid":
        return Oid.UUID;
      case "json":
        return Oid.JSON;
      case "jsonb":
        return Oid.JSONB;
      case "xml":
        return Oid.XML;
      case "refcursor":
        return Oid.REFCURSOR;
      case "point":
        return Oid.POINT;
      case "box":
        return Oid.BOX;
      case "circle":
        return Oid.CIRCLE;
      case "line":
        return Oid.LINE;
      case "lseg":
        return Oid.LSEG;
      case "path":
        return Oid.PATH;
      case "polygon":
        return Oid.POLYGON;
      case "money":
        return Oid.MONEY;
      case "bit":
        return Oid.BIT;
      case "varbit":
      case "bit varying":
        return Oid.VARBIT;
      case "interval":
        return Oid.INTERVAL;
      case "oid":
        return Oid.OID;
      case "name":
        return Oid.NAME;
      default:
        return Oid.UNSPECIFIED;
    }
  }

  /**
   * Returns the JDBC type code for a given Java class.
   * This is used by setObject(int, Object) to infer the target SQL type.
   *
   * @param javaClass the Java class
   * @return the JDBC type code (from {@link Types}), or {@link Types#OTHER} if unknown
   */
  public static int getSqlTypeForJavaClass(Class<?> javaClass) {
    // Primitive wrappers and common types
    if (javaClass == String.class || javaClass == Character.class) {
      return Types.VARCHAR;
    }
    if (javaClass == Integer.class || javaClass == int.class) {
      return Types.INTEGER;
    }
    if (javaClass == Long.class || javaClass == long.class) {
      return Types.BIGINT;
    }
    if (javaClass == Short.class || javaClass == short.class) {
      return Types.SMALLINT;
    }
    if (javaClass == Byte.class || javaClass == byte.class) {
      return Types.TINYINT;
    }
    if (javaClass == Float.class || javaClass == float.class) {
      return Types.REAL;
    }
    if (javaClass == Double.class || javaClass == double.class) {
      return Types.DOUBLE;
    }
    if (javaClass == Boolean.class || javaClass == boolean.class) {
      return Types.BOOLEAN;
    }
    if (javaClass == BigDecimal.class) {
      return Types.NUMERIC;
    }

    // Date/Time types
    if (java.sql.Date.class.isAssignableFrom(javaClass)) {
      return Types.DATE;
    }
    if (java.sql.Time.class.isAssignableFrom(javaClass)) {
      return Types.TIME;
    }
    if (java.sql.Timestamp.class.isAssignableFrom(javaClass)) {
      return Types.TIMESTAMP;
    }

    // Binary
    if (javaClass == byte[].class) {
      return Types.BINARY;
    }

    // LOB types
    if (java.sql.Blob.class.isAssignableFrom(javaClass)) {
      return Types.BLOB;
    }
    if (java.sql.Clob.class.isAssignableFrom(javaClass)) {
      return Types.CLOB;
    }

    // Array
    if (Array.class.isAssignableFrom(javaClass) || javaClass.isArray()) {
      return Types.ARRAY;
    }

    // SQLXML
    if (java.sql.SQLXML.class.isAssignableFrom(javaClass)) {
      return Types.SQLXML;
    }

    // Default to OTHER for PGobject, Map, UUID, and unknown types
    return Types.OTHER;
  }

  /**
   * Returns the default Java class name for a given JDBC SQL type.
   * This is used when OID is not available.
   *
   * @param sqlType the JDBC SQL type (from {@link java.sql.Types})
   * @return the fully qualified Java class name
   */
  public static String getDefaultJavaClassNameBySqlType(int sqlType) {
    switch (sqlType) {
      case Types.BIT:
      case Types.BOOLEAN:
        return Boolean.class.getName();
      case Types.SMALLINT:
      case Types.TINYINT:
      case Types.INTEGER:
        return Integer.class.getName();
      case Types.BIGINT:
        return Long.class.getName();
      case Types.REAL:
        return Float.class.getName();
      case Types.FLOAT:
      case Types.DOUBLE:
        return Double.class.getName();
      case Types.NUMERIC:
      case Types.DECIMAL:
        return BigDecimal.class.getName();
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        return String.class.getName();
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        return byte[].class.getName();
      case Types.DATE:
        return java.sql.Date.class.getName();
      case Types.TIME:
      case Types.TIME_WITH_TIMEZONE:
        return java.sql.Time.class.getName();
      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return java.sql.Timestamp.class.getName();
      case Types.ARRAY:
        return Array.class.getName();
      case Types.SQLXML:
        return java.sql.SQLXML.class.getName();
      case Types.REF_CURSOR:
        return java.sql.ResultSet.class.getName();
      default:
        return String.class.getName();
    }
  }
}
