package org.postgresql.sspi;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Use Waffle-JNI to support SSPI authentication when PgJDBC is running on a Windows
 * client and talking to a Windows server.
 *
 * SSPI is not supported on a non-Windows client.
 *
 *
 * @author pkajaba
 *
 */
public interface ISSPIClient {
  public boolean isSSPISupported();

  public void startSSPI() throws SQLException, IOException;

  public void continueSSPI(int msgLength) throws SQLException, IOException;

  public void dispose();
}
