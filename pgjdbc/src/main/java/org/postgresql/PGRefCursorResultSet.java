/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.ResultSet;

/**
 * A ref cursor based result set.
 * Note: as of 8.0, this interface is only present for backwards- compatibility purposes. New
 * code should call {@link ResultSet#getString} to obtain the underlying cursor name.
 */
public interface PGRefCursorResultSet {

  /**
   * @return the name of the cursor.
   * @deprecated As of 8.0, replaced with calling getString() on the ResultSet that this ResultSet
   *             was obtained from.
   */
  @Deprecated
  @Nullable String getRefCursor();
}
