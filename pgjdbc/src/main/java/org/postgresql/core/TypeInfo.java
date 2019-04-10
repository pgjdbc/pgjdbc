/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.util.Iterator;

// TODO: A "Type" class might be a good way to encapsulate these different
//       fields and consolidate the maps in the implementation.  Especially for
//       the methods that all take "oid" as a parameter.
public interface TypeInfo {
  // Class<?> here?
  void addCoreType(TypeName pgTypeName, Integer oid, Integer sqlType, String javaClass,
      Integer arrayOid);

  void addDataType(TypeName type, Class<? extends PGobject> klass) throws SQLException;

  /**
   * Look up the SQL typecode for a given type oid.
   *
   * @param oid the type's OID
   * @return the SQL type code (a constant from {@link java.sql.Types}) for the type
   * @throws SQLException if an error occurs when retrieving sql type
   */
  int getSQLType(int oid) throws SQLException;

  /**
   * Look up the SQL typecode for a given postgresql type name.
   *
   * @param pgTypeName the server type name to look up
   * @return the SQL type code (a constant from {@link java.sql.Types}) for the type
   * @throws SQLException if an error occurs when retrieving sql type
   */
  int getSQLType(TypeName pgTypeName) throws SQLException;

  /**
   * Look up the oid for a given postgresql type name. This is the inverse of
   * {@link #getPGType(int)}.
   *
   * @param pgTypeName the server type name to look up
   * @return the type's OID, or {@link Oid#UNSPECIFIED} if unknown
   * @throws SQLException if an error occurs when retrieving PG type
   */
  // TODO: Rename getOid
  int getPGType(TypeName pgTypeName) throws SQLException;

  /**
   * Look up the postgresql type name for a given oid. This is the inverse of
   * {@link #getPGType(org.postgresql.core.TypeName)}.
   *
   * @param oid the type's OID
   * @return the server type name for that OID or null if unknown
   * @throws SQLException if an error occurs when retrieving PG type
   */
  TypeName getPGType(int oid) throws SQLException;

  /**
   * Look up the oid of an array's base type given the array's type oid.
   *
   * @param oid the array type's OID
   * @return the base type's OID, or {@link Oid#UNSPECIFIED} if unknown
   * @throws SQLException if an error occurs when retrieving array element
   */
  int getPGArrayElement(int oid) throws SQLException;

  /**
   * Determine the oid of the given base postgresql type's array type.
   *
   * @param elementTypeName the base type's
   * @return the array type's OID, or {@link Oid#UNSPECIFIED} if unknown
   * @throws SQLException if an error occurs when retrieving array type
   */
  int getPGArrayType(TypeName elementTypeName) throws SQLException;

  /**
   * Determine the delimiter for the elements of the given array type oid.
   *
   * @param oid the array type's OID
   * @return the base type's array type delimiter
   * @throws SQLException if an error occurs when retrieving array delimiter
   */
  char getArrayDelimiter(int oid) throws SQLException;

  Iterator<TypeName> getPGTypeNamesWithSQLTypes();

  Class<? extends PGobject> getPGobject(TypeName type);

  // TODO: Return Class<?> here?
  String getJavaClass(int oid) throws SQLException;

  // TODO: How will this affect user-defined data types?
  //       Is this still relevant with TypeName matching quoted/unquoted?
  TypeName getTypeForAlias(TypeName alias);

  int getPrecision(int oid, int typmod);

  int getScale(int oid, int typmod);

  boolean isCaseSensitive(int oid);

  boolean isSigned(int oid);

  int getDisplaySize(int oid, int typmod);

  int getMaximumPrecision(int oid);

  boolean requiresQuoting(int oid) throws SQLException;

  /**
   * Returns true if particular sqlType requires quoting.
   * This method is used internally by the driver, so it might disappear without notice.
   *
   * @param sqlType sql type as in java.sql.Types
   * @return true if the type requires quoting
   * @throws SQLException if something goes wrong
   */
  boolean requiresQuotingSqlType(int sqlType) throws SQLException;
}
