/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core;

/**
 * Abstraction of a cursor over a returned resultset. This is an opaque interface that only provides
 * a way to close the cursor; all other operations are done by passing a ResultCursor to
 * QueryExecutor methods.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public interface ResultCursor {
  /**
   * Close this cursor. This may not immediately free underlying resources but may make it happen
   * more promptly. Closed cursors should not be passed to QueryExecutor methods.
   */
  void close();
}
