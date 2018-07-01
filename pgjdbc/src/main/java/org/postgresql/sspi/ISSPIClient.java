/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.sspi;

import java.io.IOException;
import java.sql.SQLException;

/**
 * <p>Use Waffle-JNI to support SSPI authentication when PgJDBC is running on a Windows
 * client and talking to a Windows server.</p>
 *
 * <p>SSPI is not supported on a non-Windows client.</p>
 */
public interface ISSPIClient {
  boolean isSSPISupported();

  void startSSPI() throws SQLException, IOException;

  void continueSSPI(int msgLength) throws SQLException, IOException;

  void dispose();
}
