package org.postgresql.jdbc;

import org.postgresql.core.Oid;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a PostgreSQL type.
 */
public class PgType {
  final ObjectName typeName;
  final String fullName;
  final int oid;
  final int sqlType;
  final @Nullable Class<?> javaClass;

  final int typelem;
  final int arrayOid;
  final char delimiter;

  /**
   * Constructs a new PgType.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param sqlType the corresponding JDBC SQL type
   * @param javaClass the Java class that corresponds to this type
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   */
  public PgType(ObjectName typeName, String fullName, int oid, int sqlType, @Nullable Class<?> javaClass, int typelem, int arrayOid) {
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

  /**
   * Gets the OID of the type.
   *
   * @return the OID
   */
  public int getOid() {
    return oid;
  }

  /**
   * Gets the SQL type of the type.
   *
   * @return the SQL type
   */
  public int getSqlType() {
    return sqlType;
  }

  /**
   * Gets the Java class that corresponds to this type.
   *
   * @return the Java class, or null if there is no corresponding Java class
   */
  public @Nullable Class<?> getJavaClass() {
    return javaClass;
  }

  /**
   * Gets the OID of the element type for array types.
   *
   * @return the element type OID, or Oid.UNSPECIFIED if this is not an array type
   */
  public int getTypelem() {
    return typelem;
  }

  /**
   * Gets the OID of the corresponding array type for non-array types.
   *
   * @return the array type OID, or Oid.UNSPECIFIED if there is no corresponding array type
   */
  public int getArrayOid() {
    return arrayOid;
  }

  /**
   * Gets the delimiter used in array string representations.
   *
   * @return the delimiter character
   */
  public char getDelimiter() {
    return delimiter;
  }

  /**
   * Gets the name of the type.
   *
   * @return the type name
   */
  public ObjectName getTypeName() {
    return typeName;
  }

  /**
   * Gets the full name of the type.
   *
   * @return the full name
   */
  public String getFullName() {
    return fullName;
  }
}
