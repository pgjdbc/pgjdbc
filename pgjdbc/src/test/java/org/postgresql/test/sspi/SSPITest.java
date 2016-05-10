package org.postgresql.test.sspi;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;

/*
 * These tests require a working SSPI authentication setup
 * in the database server that allows the executing user
 * to authenticate as the "sspiusername" in the build
 * configuration.
 */
public class SSPITest extends TestCase {

    /*
     * Tests that SSPI login succeeds and a query can be run.
     */
    public void testAuthorized() throws Exception {
        Properties props = new Properties();
        props.setProperty("username", TestUtil.getSSPIUser());
        Connection con = TestUtil.openDB(props);

        Statement stmt = con.createStatement();
        stmt.executeQuery("SELECT 1");

        TestUtil.closeDB(con);
    }

    /*
     * Tests that SSPI login fails with an unknown/unauthorized
     * user name.
     */
    public void testUnauthorized() throws Exception {
        Properties props = new Properties();
        props.setProperty("username", "invalid" + TestUtil.getSSPIUser());

        try {
            Connection con = TestUtil.openDB(props);
            TestUtil.closeDB(con);
            fail("Expected a PSQLException");
        } catch (PSQLException e) {
            assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState());
        }
    }

}

