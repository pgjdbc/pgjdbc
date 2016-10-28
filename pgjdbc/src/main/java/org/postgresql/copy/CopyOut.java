/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.copy;

import java.sql.SQLException;

public interface CopyOut extends CopyOperation {
  byte[] readFromCopy() throws SQLException;
}
