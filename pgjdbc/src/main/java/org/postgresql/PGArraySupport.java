package org.postgresql;

import java.sql.Array;
import java.sql.SQLException;

/**
 * Defines public PostgreSQL extensions to create {@link Array} objects.
 */
public interface PGArraySupport {

  /**
   * Creates an {@link Array} wrapping <i>elements</i>. This is similar to
   * java.sql.Connection#createArrayOf(String, Object[]), but also provides
   * support for primitive arrays.
   * 
   * @param typeName
   *          The SQL name of the type to map the <i>elements</i> to.
   * @param elements
   *          The array of objects to map.
   * @return An {@link Array} wrapping <i>elements</i>.
   * @throws SQLException
   * @see java.sql.Connection#createArrayOf(String, Object[])
   */
  public Array createArrayOf(String typeName, Object elements) throws SQLException;
}
