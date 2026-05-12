/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.Experimental;
import org.postgresql.core.Oid;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Types;
import java.util.Collections;
import java.util.List;

/**
 * Represents a PostgreSQL type.
 *
 * @since 42.8.0
 */
@Experimental("PgType API is experimental and may change in future releases")
public class PgType {
  private final ObjectName typeName;
  private final String fullName;
  private final int oid;
  private final char typtype;      // 'b'=base, 'c'=composite, 'e'=enum, 'd'=domain, 'p'=pseudo, 'r'=range, 'm'=multirange
  private final char typcategory;  // 'A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.
  private final int typtypmod;

  private final int typelem;
  private final int arrayOid;
  private final int typbasetype;   // base type OID for domains
  private final char delimiter;

  // Composite type fields (null for non-composite types, empty list if not yet loaded)
  private final @Nullable List<PgField> fields;

  /**
   * Constructs a new PgType for non-composite types.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   */
  public PgType(ObjectName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype,
        oid == Oid.BOX || oid == Oid.BOX_ARRAY ? ';' : ',', null);
  }

  /**
   * Constructs a new PgType with delimiter from database.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   * @param delimiter the array delimiter character from pg_type.typdelim
   */
  public PgType(ObjectName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype, char delimiter) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype, delimiter, null);
  }

  /**
   * Constructs a new PgType with composite type fields.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   * @param fields the list of fields for composite types, or null for non-composite types
   */
  public PgType(ObjectName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype,
                @Nullable List<PgField> fields) {
    this(typeName, fullName, oid, typtype, typcategory, typtypmod, typelem, arrayOid, typbasetype,
        oid == Oid.BOX || oid == Oid.BOX_ARRAY ? ';' : ',', fields);
  }

  /**
   * Constructs a new PgType with all parameters.
   *
   * @param typeName the type name
   * @param fullName the full name of the type
   * @param oid the OID of the type
   * @param typtype the type type ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.)
   * @param typcategory the type category ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   * @param typtypmod the type modifier
   * @param typelem for array types, the OID of the element type
   * @param arrayOid for non-array types, the OID of the corresponding array type
   * @param typbasetype for domain types, the OID of the base type
   * @param delimiter the array delimiter character from pg_type.typdelim
   * @param fields the list of fields for composite types, or null for non-composite types
   */
  public PgType(ObjectName typeName, String fullName, int oid, char typtype, char typcategory,
                int typtypmod, int typelem, int arrayOid, int typbasetype, char delimiter,
                @Nullable List<PgField> fields) {
    this.typeName = typeName;
    this.fullName = fullName;
    this.oid = oid;
    this.typtype = typtype;
    this.typcategory = typcategory;
    this.typtypmod = typtypmod;
    this.typelem = typelem;
    this.arrayOid = arrayOid;
    this.typbasetype = typbasetype;
    this.delimiter = delimiter;
    this.fields = fields != null ? Collections.unmodifiableList(fields) : null;
  }

  /**
   * Returns the JDBC SQL type for the given PostgreSQL type characteristics.
   * For built-in types, uses OID for precise mapping. For user-defined types,
   * falls back to typcategory/typtype.
   *
   * @param oid the type OID
   * @param typcategory the type category
   * @param typtype the type type
   * @return the JDBC SQL type constant from {@link java.sql.Types}
   */
  public static int toJdbcSqlType(int oid, char typcategory, char typtype) {
    // Domains are JDBC DISTINCT regardless of base typcategory (per JDBC spec).
    // This precedence matters for callers like PgDatabaseMetaData.getColumns,
    // where the jdbc3 contract reports DATA_TYPE=DISTINCT for domain columns
    // while still keeping the base type's COLUMN_SIZE / DECIMAL_DIGITS.
    if (typtype == 'd') {
      return Types.DISTINCT;
    }
    // For built-in types, use OID for precise mapping
    int sqlType = getSqlTypeByOid(oid);
    if (sqlType != Types.OTHER) {
      return sqlType;
    }
    // Fall back to typcategory/typtype for user-defined types
    return toJdbcSqlType(typcategory, typtype);
  }

  /**
   * Returns the JDBC SQL type based on typcategory and typtype only.
   * Used for user-defined types where OID is not known in advance.
   */
  public static int toJdbcSqlType(char typcategory, char typtype) {
    // Domain precedence over the inherited typcategory (see toJdbcSqlType(oid, ...)).
    if (typtype == 'd') {
      return Types.DISTINCT;
    }
    switch (typcategory) {
      case 'A':
        return Types.ARRAY;
      case 'B':
        return Types.BOOLEAN; // Boolean category for user-defined types
      case 'D':
        return Types.TIMESTAMP;
      case 'N':
        return Types.NUMERIC;
      case 'S':
        return Types.VARCHAR;
      default:
        break;
    }
    switch (typtype) {
      case 'c':
        return Types.STRUCT;
      case 'e':
        return Types.VARCHAR;
      default:
        break;
    }
    return Types.OTHER;
  }

  /**
   * Returns the JDBC SQL type for well-known PostgreSQL OIDs.
   * Returns Types.OTHER if the OID is not a known built-in type.
   */
  private static int getSqlTypeByOid(int oid) {
    switch (oid) {
      // Numeric types
      case Oid.INT2:
        return Types.SMALLINT;
      case Oid.INT4:
        return Types.INTEGER;
      case Oid.INT8:
        return Types.BIGINT;
      case Oid.OID:
        return Types.BIGINT;
      case Oid.FLOAT4:
        return Types.REAL;
      case Oid.FLOAT8:
        return Types.DOUBLE;
      case Oid.MONEY:
        return Types.DOUBLE;
      case Oid.NUMERIC:
        return Types.NUMERIC;
      // Boolean - returns Types.BIT for backward compatibility.
      // Types.BOOLEAN would be more semantically correct, but changing this
      // would break existing applications that rely on the current behavior.
      // See: https://github.com/pgjdbc/pgjdbc/issues/3230
      // See: https://github.com/pgjdbc/pgjdbc/pull/895
      // Use connection property map.pg_type.boolean=boolean to get Types.BOOLEAN
      case Oid.BOOL:
        return Types.BIT;
      // Bit strings
      case Oid.BIT:
        return Types.BIT;
      case Oid.VARBIT:
        return Types.OTHER;
      // String types
      case Oid.VARCHAR:
        return Types.VARCHAR;
      case Oid.TEXT:
        return Types.VARCHAR;
      case Oid.BPCHAR:
        return Types.CHAR;
      case Oid.NAME:
        return Types.VARCHAR;
      // Binary
      case Oid.BYTEA:
        return Types.BINARY;
      // Date/Time
      case Oid.DATE:
        return Types.DATE;
      case Oid.TIME:
      case Oid.TIMETZ:
        return Types.TIME;
      case Oid.TIMESTAMP:
      case Oid.TIMESTAMPTZ:
        // Legacy pgjdbc reports both timestamp and timestamptz as
        // Types.TIMESTAMP via DatabaseMetaData / ResultSetMetaData; switching
        // timestamptz to Types.TIMESTAMP_WITH_TIMEZONE (2014) breaks
        // downstream code (Hibernate / jOOQ type switches, our own
        // DatabaseMetaDataTest, etc.) that pattern-match on Types.TIMESTAMP.
        return Types.TIMESTAMP;
      // Special types
      case Oid.REFCURSOR:
        return Types.REF_CURSOR;
      case Oid.XML:
        return Types.SQLXML;
      default:
        return Types.OTHER;
    }
  }

  /**
   * Gets the OID of the type.
   *
   * @return the OID
   */
  public int getOid() {
    return oid;
  }

  public int getTyptypmod() {
    return typtypmod;
  }

  /**
   * Gets the typtype value.
   *
   * @return the typtype ('b'=base, 'c'=composite, 'e'=enum, 'd'=domain, 'p'=pseudo, 'r'=range, 'm'=multirange)
   */
  public char getTyptype() {
    return typtype;
  }

  /**
   * Gets the typcategory value.
   *
   * @return the typcategory ('A'=array, 'B'=boolean, 'N'=numeric, 'S'=string, etc.)
   */
  public char getTypcategory() {
    return typcategory;
  }

  /**
   * Gets the base type OID for domain types.
   *
   * @return the base type OID, or 0 if this is not a domain type
   */
  public int getTypbasetype() {
    return typbasetype;
  }

  /**
   * Gets the SQL type of the type, computed from OID and typcategory/typtype.
   * For built-in types, uses OID for precise mapping. For user-defined types,
   * falls back to typcategory/typtype.
   *
   * @return the SQL type (a constant from {@link java.sql.Types})
   */
  public int getSqlType() {
    return toJdbcSqlType(oid, typcategory, typtype);
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

  /**
   * Returns whether this is a composite type (struct).
   *
   * @return true if this is a composite type (typtype='c')
   */
  public boolean isComposite() {
    return typtype == 'c';
  }

  /**
   * Returns whether this is a domain type.
   *
   * @return true if this is a domain type (typtype='d')
   */
  public boolean isDomain() {
    return typtype == 'd';
  }

  /**
   * Returns whether this is an enum type.
   *
   * @return true if this is an enum type (typtype='e')
   */
  public boolean isEnum() {
    return typtype == 'e';
  }

  /**
   * Returns whether this is an array type.
   *
   * @return true if this is an array type (typcategory='A')
   */
  public boolean isArray() {
    return typcategory == 'A';
  }

  /**
   * Gets the fields of a composite type.
   * For non-composite types, returns null.
   * For composite types whose fields haven't been loaded yet, returns null.
   *
   * @return the list of fields, or null if not a composite type or fields not loaded
   */
  public @Nullable List<PgField> getFields() {
    return fields;
  }

  /**
   * Returns whether this type has its fields loaded.
   * Only meaningful for composite types.
   *
   * @return true if this is a composite type and its fields have been loaded
   */
  public boolean hasFieldsLoaded() {
    return fields != null;
  }

  /**
   * Creates a copy of this PgType with the specified fields.
   * Used to update a composite type after loading its fields.
   *
   * @param fields the list of fields
   * @return a new PgType with the fields set
   */
  public PgType withFields(List<PgField> fields) {
    return new PgType(typeName, fullName, oid, typtype, typcategory,
        typtypmod, typelem, arrayOid, typbasetype, fields);
  }

  public boolean isCaseSensitive() {
    return isCaseSensitive(oid);
  }

  public static boolean isCaseSensitive(int oid) {
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

  public boolean isSigned() {
    return isSigned(oid);
  }

  public static boolean isSigned(int oid) {
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

  public boolean requiresQuoting() {
    return requiresQuotingSqlType(getSqlType());
  }

  public static boolean requiresQuotingSqlType(int sqlType) {
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
      default:
        return true;
    }
  }
}
