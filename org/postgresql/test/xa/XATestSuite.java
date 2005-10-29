package org.postgresql.test.xa;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;

import junit.framework.TestSuite;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc3.Jdbc3SavepointTest;

public class XATestSuite extends TestSuite {
	public static TestSuite suite() throws Exception {
		DriverManager.setLogWriter(new PrintWriter(System.out));
		Class.forName("org.postgresql.Driver");
		TestSuite suite = new TestSuite();
		Connection connection = TestUtil.openDB();

		try {
			if (TestUtil.haveMinimumServerVersion(connection, "8.1")) {
				suite.addTestSuite(XADataSourceTest.class);
			}
		} finally {
			connection.close();
		}
		return suite;
	}
}
