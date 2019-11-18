/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

/**
 * A ref cursor based result set.
 *
 * @deprecated As of 8.0, this interface is only present for backwards- compatibility purposes. New
 *             code should call getString() on the ResultSet that contains the refcursor to obtain
 *             the underlying cursor name.
 */
@Deprecated
public interface PGRefCursorResultSet {

  /**
   * @return the name of the cursor.
   * @deprecated As of 8.0, replaced with calling getString() on the ResultSet that this ResultSet
   *             was obtained from.
   */
  @Deprecated
  String getRefCursor();
}
