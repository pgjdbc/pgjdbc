/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc2;

import org.postgresql.core.BaseConnection;
import org.postgresql.udt.UdtMap;
import org.postgresql.udt.ValueAccess;

/**
 * Implement this interface and register the its instance to ArrayAssistantRegistry, to let Postgres
 * driver to support more array type.
 *
 * @author Minglei Tu
 */
// TODO: Does this need to exist, or can we leverage the existing registration
//       for all PGobject, with UUID being PGobject somehow?  Or, is UUID handled
//       in a special case elsewhere, too?
public interface ArrayAssistant {
  /**
   * get array base type.
   *
   * @return array base type
   */
  Class<?> baseType();

  /**
   * build a array element from its binary bytes.
   *
   * @param bytes input bytes
   * @param pos position in input array
   * @param len length of the element
   * @return array element from its binary bytes
   */
  Object buildElement(byte[] bytes, int pos, int len);

  /**
   * build an array element from its literal string.
   *
   * @param literal string representation of array element
   * @return array element
   */
  Object buildElement(String literal);

  /**
   * Gets the {@link ValueAccess} that will be used for user-defined data types
   * based on the element type of this array.
   *
   * @param connection the current connection
   * @param oid the oid of this array type
   * @param value the element value object from {@link #buildElement(java.lang.String)} or {@link #buildElement(byte[], int, int)}
   * @param udtMap the current user-defined data type mapping
   * @return the implementation of {@link ValueAccess} for elements of this type
   */
  ValueAccess getValueAccess(BaseConnection connection, int oid, Object value, UdtMap udtMap);
}
