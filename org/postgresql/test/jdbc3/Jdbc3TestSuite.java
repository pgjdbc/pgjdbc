package org.postgresql.test.jdbc3;

import junit.framework.TestSuite;

/*
 * Executes all known tests for JDBC3
 */
public class Jdbc3TestSuite extends TestSuite
{

	/*
	 * The main entry point for JUnit
	 */
	public static TestSuite suite()
	{
        TestSuite suite = new TestSuite();
	suite.addTestSuite(Jdbc3SavepointTest.class);
        return suite;
	}
}
