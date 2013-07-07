/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

import java.sql.*;
import java.util.Properties;

public class StringTypeParameterTest extends TestCase {

    private Connection _conn;

    public StringTypeParameterTest(String name) {
        super(name);
    }

    protected void setUp(String stringType) throws Exception {
        Properties props = new Properties();
        if(stringType != null) {
            props.put("stringtype", stringType);
        }
        _conn = TestUtil.openDB(props);
        TestUtil.createEnumType(_conn, "mood", "'happy', 'sad'");
        TestUtil.createTable(_conn, "stringtypetest", "m mood");
    }

    protected void tearDown() throws SQLException {
        if(_conn != null) {
            TestUtil.dropTable(_conn, "stringtypetest");
            TestUtil.dropType(_conn, "mood");
            TestUtil.closeDB(_conn);
        }
    }

    public void testParameterStringTypeVarchar() throws Exception {
        if (!TestUtil.isProtocolVersion(_conn, 3))
            return;
        testParameterVarchar("varchar");
    }

    public void testParameterStringTypeNotSet() throws Exception {
        if (!TestUtil.isProtocolVersion(_conn, 3))
            return;
        testParameterVarchar(null);
    }

    private void testParameterVarchar(String param) throws Exception {
        setUp(param);

        PreparedStatement update = _conn.prepareStatement("insert into stringtypetest (m) values (?)");
        update.setString(1, "sad");
        try {
            update.executeUpdate();
            fail("Expected exception thrown");
        } catch(SQLException e) {
            // expected
        }

        update.clearParameters();
        update.setObject(1, "sad", Types.VARCHAR);
        try {
            update.executeUpdate();
            fail("Expected exception thrown");
        } catch(SQLException e) {
            // expected
        }

        update.clearParameters();
        update.setObject(1, "happy", Types.OTHER);
        update.executeUpdate();
        // all good
        update.close();

        PreparedStatement query = _conn.prepareStatement("select * from stringtypetest where m = ? or m = ?");
        query.setString(1, "sad");
        try {
            query.executeQuery();
            fail("Expected exception thrown");
        } catch(SQLException e) {
            // expected
        }

        query.clearParameters();
        query.setObject(2, "sad", Types.VARCHAR);
        try {
            query.executeQuery();
            fail("Expected exception thrown");
        } catch(SQLException e) {
            // expected
        }

        query.clearParameters();
        query.setObject(1, "happy", Types.OTHER);
        query.executeQuery().close();
        // all good
        query.close();

    }

    public void testParameterUnspecified() throws Exception {
        setUp("unspecified");

        PreparedStatement update = _conn.prepareStatement("insert into stringtypetest (m) values (?)");
        update.setString(1, "happy");
        update.executeUpdate();
        // all good

        update.clearParameters();
        update.setObject(1, "happy", Types.VARCHAR);
        update.executeUpdate();
        // all good
        update.close();

        PreparedStatement query = _conn.prepareStatement("select * from stringtypetest where m = ?");
        query.setString(1, "happy");
        query.executeQuery().close();

        query.clearParameters();
        query.setObject(1, "happy", Types.VARCHAR);
        query.executeQuery().close();

        // all good
        query.close();

    }
}
