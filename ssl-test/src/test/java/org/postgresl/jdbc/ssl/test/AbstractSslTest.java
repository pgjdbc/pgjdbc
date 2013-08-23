package org.postgresql.jdbc.ssl.test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.Assert;

public abstract class AbstractSslTest {
	String host = "localhost";
	int port = 15432;
	String database = "test";
	String username = "test";
	String password = "test";

	protected Connection getConnection(Properties info) throws SQLException {
		String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
		info.setProperty("user", username);
		info.setProperty("password", password);
		return DriverManager.getConnection(url, info);
	}

	/**
	 * Connects to the database with the given connection properties and
	 * then verifies that connection is using SSL.
	 */
	protected void testConnect(Properties info, boolean sslExpected) throws SQLException {
		Connection conn = null;
		try {
			conn = getConnection(info);
			Statement stmt = conn.createStatement();
			// Basic SELECT test:
			ResultSet rs = stmt.executeQuery("SELECT 1");
			rs.next();
			Assert.assertEquals(1, rs.getInt(1));
			rs.close();
			// Verify SSL usage is as expected:
			rs = stmt.executeQuery("SELECT ssl_is_used()");
			rs.next();
			boolean sslActual = rs.getBoolean(1);			
			Assert.assertEquals(sslExpected, sslActual);
			stmt.close();
		} finally {
			if (conn != null) {
				try {
					conn.close();
				} catch (Exception e) {
				}
			}
		}
	}
}