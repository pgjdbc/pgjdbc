/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.JavaTypeRegistry;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Iterator;
import java.util.List;

/**
 * Provides type information for PostgreSQL types.
 *
 * <p>The primary API methods are:
 * <ul>
 *   <li>{@link #getPgTypeByOid(int)} - get type by OID</li>
 *   <li>{@link #getPgTypeByPgName(String)} - get type by name</li>
 *   <li>{@link #getFields(int)} - get composite type fields</li>
 * </ul>
 *
 * <p>Many methods in this interface are deprecated and will be removed in a future version.
 * They delegate to the new API methods above.</p>
 */
public interface TypeInfo {

  // ==================== NEW API ====================

  /**
   * Adds the given type to the cache.
   *
   * @param pgType the type to add to the cache.
   */
  void add(PgType pgType);

  /**
   * Gets the PostgreSQL type information by OID.
   *
   * @param oid the type's OID
   * @return the PgType for the given OID
   * @throws SQLException if an error occurs or the type is not found
   */
  PgType getPgTypeByOid(int oid) throws SQLException;

  /**
   * Gets the PostgreSQL type information by type name.
   *
   * @param pgTypeName the PostgreSQL type name (can be qualified with schema)
   * @return the PgType for the given name
   * @throws SQLException if an error occurs or the type is not found
   */
  PgType getPgTypeByPgName(String pgTypeName) throws SQLException;

  /**
   * Gets the fields for a composite type.
   * Fields are loaded eagerly when first accessed and cached.
   *
   * @param oid the OID of the composite type
   * @return the list of fields, or an empty list if not a composite type
   * @throws SQLException if a database error occurs
   */
  List<PgField> getFields(int oid) throws SQLException;

  /**
   * Registers a custom PGobject subclass for a PostgreSQL type.
   *
   * @param type the PostgreSQL type name
   * @param klass the PGobject subclass to use for this type
   * @throws SQLException if registration fails
   */
  void addDataType(String type, Class<? extends PGobject> klass) throws SQLException;

  /**
   * Gets the registered PGobject subclass for a PostgreSQL type.
   *
   * @param type the PostgreSQL type name
   * @return the registered class, or null if none registered
   */
  @Nullable Class<? extends PGobject> getPGobject(String type);

  /**
   * Gets the codec registry for type encoding/decoding.
   *
   * @return the codec registry
   */
  CodecRegistry getCodecRegistry();

  /**
   * Gets the Java type registry for Java to PostgreSQL type mappings.
   *
   * @return the Java type registry
   */
  JavaTypeRegistry getJavaTypeRegistry();

  /**
   * Gets the precision for a type with the given typmod.
   *
   * @param oid the type's OID
   * @param typmod the type modifier
   * @return the precision
   * @throws SQLException if an error occurs
   */
  int getPrecision(int oid, int typmod) throws SQLException;

  /**
   * Gets the scale for a type with the given typmod.
   *
   * @param oid the type's OID
   * @param typmod the type modifier
   * @return the scale
   * @throws SQLException if an error occurs
   */
  int getScale(int oid, int typmod) throws SQLException;

  /**
   * Gets the display size for a type with the given typmod.
   *
   * @param oid the type's OID
   * @param typmod the type modifier
   * @return the display size
   * @throws SQLException if an error occurs
   */
  int getDisplaySize(int oid, int typmod) throws SQLException;

  /**
   * Gets the maximum precision for a type.
   *
   * @param oid the type's OID
   * @return the maximum precision
   * @throws SQLException if an error occurs
   */
  int getMaximumPrecision(int oid) throws SQLException;

  // ==================== DEPRECATED API ====================
  // These methods delegate to the new API and will be removed in a future version.

  /**
   * Look up the SQL typecode for a given type oid.
   *
   * @param oid the type's OID
   * @return the SQL type code (a constant from {@link java.sql.Types}) for the type
   * @throws SQLException if an error occurs when retrieving sql type
   * @deprecated Use {@link #getPgTypeByOid(int)} and {@link PgType#getSqlType()} instead.
   */
  @Deprecated
  default int getSQLType(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return Types.OTHER;
    }
    return getPgTypeByOid(oid).getSqlType();
  }

  /**
   * Look up the SQL typecode for a given postgresql type name.
   *
   * @param pgTypeName the server type name to look up
   * @return the SQL type code (a constant from {@link java.sql.Types}) for the type
   * @throws SQLException if an error occurs when retrieving sql type
   * @deprecated Use {@link #getPgTypeByPgName(String)} and {@link PgType#getSqlType()} instead.
   */
  @Deprecated
  default int getSQLType(String pgTypeName) throws SQLException {
    if (pgTypeName.endsWith("[]")) {
      return java.sql.Types.ARRAY;
    }
    return getPgTypeByPgName(pgTypeName).getSqlType();
  }

  /**
   * Gets the array OID for a Java class.
   *
   * @param javaClass the Java class
   * @return the array type OID, or {@link Oid#UNSPECIFIED} if unknown
   * @throws SQLException if an error occurs
   * @deprecated Use {@link org.postgresql.jdbc.JavaTypeRegistry#getArrayOidForJavaClass(Class)} instead.
   */
  @Deprecated
  int getJavaArrayType(Class<?> javaClass) throws SQLException;

  /**
   * Look up the oid for a given postgresql type name.
   *
   * @param pgTypeName the server type name to look up
   * @return the type's OID, or 0 if unknown
   * @throws SQLException if an error occurs when retrieving PG type
   * @deprecated Use {@link #getPgTypeByPgName(String)} and {@link PgType#getOid()} instead.
   */
  @Deprecated
  default int getPGType(String pgTypeName) throws SQLException {
    return getPgTypeByPgName(pgTypeName).getOid();
  }

  /**
   * Look up the postgresql type name for a given oid.
   *
   * @param oid the type's OID
   * @return the server type name for that OID or null if unknown
   * @throws SQLException if an error occurs when retrieving PG type
   * @deprecated Use {@link #getPgTypeByOid(int)} and {@link PgType#getFullName()} instead.
   */
  @Deprecated
  default @Nullable String getPGType(int oid) throws SQLException {
    return getPgTypeByOid(oid).getFullName();
  }

  /**
   * Look up the oid of an array's base type given the array's type oid.
   *
   * @param oid the array type's OID
   * @return the base type's OID, or 0 if unknown
   * @throws SQLException if an error occurs when retrieving array element
   * @deprecated Use {@link #getPgTypeByOid(int)} and {@link PgType#getTypelem()} instead.
   */
  @Deprecated
  default int getPGArrayElement(int oid) throws SQLException {
    if (oid == Oid.UNSPECIFIED) {
      return Oid.UNSPECIFIED;
    }
    return getPgTypeByOid(oid).getTypelem();
  }

  /**
   * Determine the oid of the given base postgresql type's array type.
   *
   * @param elementTypeName the base type's name
   * @return the array type's OID, or 0 if unknown
   * @throws SQLException if an error occurs when retrieving array type
   * @deprecated Use {@link #getPgTypeByPgName(String)} and {@link PgType#getArrayOid()} instead.
   */
  @Deprecated
  default int getPGArrayType(@Nullable String elementTypeName) throws SQLException {
    return getPgTypeByPgName(elementTypeName).getArrayOid();
  }

  /**
   * Determine the delimiter for the elements of the given array type oid.
   *
   * @param oid the array type's OID
   * @return the base type's array type delimiter
   * @throws SQLException if an error occurs when retrieving array delimiter
   * @deprecated Use {@link #getPgTypeByOid(int)} and {@link PgType#getDelimiter()} instead.
   */
  @Deprecated
  default char getArrayDelimiter(int oid) throws SQLException {
    return getPgTypeByOid(oid).getDelimiter();
  }

  /**
   * @deprecated This method is not implemented and will be removed.
   */
  @Deprecated
  Iterator<Integer> getPGTypeOidsWithSQLTypes();

  /**
   * Gets the default Java class name for a PostgreSQL type.
   *
   * @param oid the type's OID
   * @return the fully qualified Java class name
   * @throws SQLException if an error occurs
   * @deprecated Use {@link JavaTypeRegistry#getDefaultJavaClassName(int)} instead.
   */
  @Deprecated
  default String getJavaClass(int oid) throws SQLException {
    return JavaTypeRegistry.getDefaultJavaClassName(oid);
  }

  /**
   * Resolves a type alias to the canonical type name.
   *
   * @param alias the type alias
   * @return the canonical type name
   * @deprecated Type aliases are resolved internally.
   */
  @Deprecated
  @Nullable String getTypeForAlias(@Nullable String alias);

  /**
   * Returns whether a type is case-sensitive.
   *
   * @param oid the type's OID
   * @return true if the type is case-sensitive
   * @throws SQLException if an error occurs
   * @deprecated Use {@link PgType#isCaseSensitive(int)} instead.
   */
  @Deprecated
  default boolean isCaseSensitive(int oid) throws SQLException {
    return PgType.isCaseSensitive(oid);
  }

  /**
   * Returns whether a type is signed.
   *
   * @param oid the type's OID
   * @return true if the type is signed
   * @throws SQLException if an error occurs
   * @deprecated Use {@link PgType#isSigned(int)} instead.
   */
  @Deprecated
  default boolean isSigned(int oid) throws SQLException {
    return PgType.isSigned(oid);
  }

  /**
   * Returns whether a type requires quoting in SQL.
   *
   * @param oid the type's OID
   * @return true if the type requires quoting
   * @throws SQLException if an error occurs
   * @deprecated Use {@link #getPgTypeByOid(int)} and {@link PgType#requiresQuoting()} instead.
   */
  @Deprecated
  default boolean requiresQuoting(int oid) throws SQLException {
    return getPgTypeByOid(oid).requiresQuoting();
  }

  /**
   * Converts a long OID value to int.
   *
   * @param oid the oid as a long
   * @return the oid as an int
   * @throws SQLException if the value is out of range
   * @deprecated OID conversion is handled internally.
   */
  @Deprecated
  default int longOidToInt(long oid) throws SQLException {
    if ((oid & 0xFFFF_FFFF_0000_0000L) != 0) {
      throw new org.postgresql.util.PSQLException(
          org.postgresql.util.GT.tr("Value is not an OID: {0}", oid),
          org.postgresql.util.PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) oid;
  }

  /**
   * Converts an int OID value to long.
   *
   * @param oid the oid as an int
   * @return the oid as a long (unsigned)
   * @deprecated OID conversion is handled internally.
   */
  @Deprecated
  default long intOidToLong(int oid) {
    return ((long) oid) & 0xFFFFFFFFL;
  }
}
