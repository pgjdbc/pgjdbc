/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/CallableStmtTest.java,v 1.15 2005/06/14 19:32:28 davec Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import java.sql.*;

import junit.framework.TestCase;

/*
 * CallableStatement tests.
 * @author Paul Bethe
 */
public class CallableStmtTest extends TestCase
{
    private Connection con;

    public CallableStmtTest (String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        Statement stmt = con.createStatement ();
        stmt.execute ("CREATE OR REPLACE FUNCTION testspg__getString (varchar) " +
                      "RETURNS varchar AS ' DECLARE inString alias for $1; begin " +
                      "return ''bob''; end; ' LANGUAGE 'plpgsql';");
        stmt.execute ("CREATE OR REPLACE FUNCTION testspg__getDouble (float) " +
                      "RETURNS float AS ' DECLARE inString alias for $1; begin " +
                      "return 42.42; end; ' LANGUAGE 'plpgsql';");
        stmt.execute ("CREATE OR REPLACE FUNCTION testspg__getInt (int) RETURNS int " +
                      " AS 'DECLARE inString alias for $1; begin " +
                      "return 42; end;' LANGUAGE 'plpgsql';");      
        stmt.execute ("CREATE OR REPLACE FUNCTION testspg__getShort (int2) RETURNS int2 " +
                " AS 'DECLARE inString alias for $1; begin " +
                "return 42; end;' LANGUAGE 'plpgsql';");
        stmt.execute ("CREATE OR REPLACE FUNCTION testspg__getNumeric (numeric) " +
                      "RETURNS numeric AS ' DECLARE inString alias for $1; " +
                      "begin return 42; end; ' LANGUAGE 'plpgsql';");
        stmt.execute ("CREATE OR REPLACE FUNCTION testspg__getNumericWithoutArg() " +
                "RETURNS numeric AS '  " +
                "begin return 42; end; ' LANGUAGE 'plpgsql';");
        stmt.execute("CREATE OR REPLACE FUNCTION getarray() RETURNS int[] as 'SELECT ''{1,2}''::int[];' LANGUAGE 'sql'");
        stmt.execute("CREATE OR REPLACE FUNCTION raisenotice() RETURNS int as 'BEGIN RAISE NOTICE ''hello'';  RAISE NOTICE ''goodbye''; RETURN 1; END;' LANGUAGE 'plpgsql'");
        stmt.close ();
    }

    protected void tearDown() throws Exception
    {
        Statement stmt = con.createStatement ();
        stmt.execute ("drop FUNCTION testspg__getString (varchar);");
        stmt.execute ("drop FUNCTION testspg__getDouble (float);");
        stmt.execute ("drop FUNCTION testspg__getInt (int);");
        stmt.execute ("drop FUNCTION testspg__getShort(int2)");
        stmt.execute ("drop FUNCTION testspg__getNumeric (numeric);");
        stmt.execute ("drop FUNCTION testspg__getNumericWithoutArg ();");
        stmt.execute ("DROP FUNCTION getarray();");
        stmt.execute ("DROP FUNCTION raisenotice();");
        TestUtil.closeDB(con);
    }


    final String func = "{ ? = call ";
    final String pkgName = "testspg__";
    // protected void runTest () throws Throwable {
    //testGetString ();
    //}

    public void testGetDouble () throws Throwable
    {
        CallableStatement call = con.prepareCall (func + pkgName + "getDouble (?) }");
        call.setDouble (2, (double)3.04);
        call.registerOutParameter (1, Types.DOUBLE);
        call.execute ();
        assertEquals(42.42, call.getDouble(1), 0.00001);
    }

    public void testGetInt () throws Throwable
    {
        CallableStatement call = con.prepareCall (func + pkgName + "getInt (?) }");
        call.setInt (2, 4);
        call.registerOutParameter (1, Types.INTEGER);
        call.execute ();
        assertEquals(42, call.getInt(1));
    }

    public void testGetShort () throws Throwable
    {
        if ( TestUtil.isProtocolVersion(con, 3) )
        {    
	        CallableStatement call = con.prepareCall (func + pkgName + "getShort (?) }");
	        call.setShort (2, (short)4);
	        call.registerOutParameter (1, Types.SMALLINT);
	        call.execute ();
	        assertEquals(42, call.getShort(1));
        }
    }
    public void testGetNumeric () throws Throwable
    {
        CallableStatement call = con.prepareCall (func + pkgName + "getNumeric (?) }");
        call.setBigDecimal (2, new java.math.BigDecimal(4));
        call.registerOutParameter (1, Types.NUMERIC);
        call.execute ();
        assertEquals(new java.math.BigDecimal(42), call.getBigDecimal(1));
    }
    
    public void testGetNumericWithoutArg () throws Throwable
    {
        CallableStatement call = con.prepareCall (func + pkgName + "getNumericWithoutArg () }");
        call.registerOutParameter (1, Types.NUMERIC);
        call.execute ();
        assertEquals(new java.math.BigDecimal(42), call.getBigDecimal(1));
    }

    public void testGetString () throws Throwable
    {
        CallableStatement call = con.prepareCall (func + pkgName + "getString (?) }");
        call.setString (2, "foo");
        call.registerOutParameter (1, Types.VARCHAR);
        call.execute ();
        assertEquals("bob", call.getString(1));

    }

    public void testGetArray() throws SQLException
    {
        CallableStatement call = con.prepareCall("{ ? = call getarray()}");
        call.registerOutParameter(1, Types.ARRAY);
        call.execute();
        Array arr = call.getArray(1);
        ResultSet rs = arr.getResultSet();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertTrue(!rs.next());
    }

    public void testRaiseNotice() throws SQLException
    {
        CallableStatement call = con.prepareCall("{? = call raisenotice()}");
        call.registerOutParameter(1, Types.INTEGER);
        call.execute();
        SQLWarning warn = call.getWarnings();
        assertNotNull(warn);
        assertEquals("hello", warn.getMessage());
        warn = warn.getNextWarning();
        assertNotNull(warn);
        assertEquals("goodbye", warn.getMessage());
        assertEquals(1, call.getInt(1));
    }

    public void testBadStmt () throws Throwable
    {
        tryOneBadStmt ("{ ?= " + pkgName + "getString (?) }");
        tryOneBadStmt ("{ ?= call getString (?) ");
        tryOneBadStmt ("{ = ? call getString (?); }");
    }

    protected void tryOneBadStmt (String sql) throws SQLException
    {
        try
        {
            con.prepareCall (sql);
            fail("Bad statement (" + sql + ") was not caught.");

        }
        catch (SQLException e)
        {
        }
    }

}
