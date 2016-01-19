package org.postgresql.sspi;

import java.io.IOException;
import java.sql.SQLException;

import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * Use Waffle-JNI to support SSPI authentication when PgJDBC is running on a Windows 
 * client and talking to a Windows server.
 * 
 * SSPI is not supported on a non-Windows client.
 * 
 * 
 * @author craig
 *
 */
public class ISSPIClient {

	public boolean isSSPISupported() {
		return false;
	}
	
	public void startSSPI() throws SQLException, IOException {
	}
	
	public void continueSSPI(int msgLength) throws SQLException, IOException {
	}
	
	public void dispose() {
	}

}
