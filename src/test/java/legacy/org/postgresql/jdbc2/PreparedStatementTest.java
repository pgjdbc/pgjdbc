/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.TestUtil;
import legacy.org.postgresql.util.BrokenInputStream;
import legacy.org.postgresql.util.PGobject;
import junit.framework.TestCase;
import java.io.*;
import java.sql.*;
import java.math.BigDecimal;


public class PreparedStatementTest extends TestCase
{

    private Connection conn;

    public PreparedStatementTest(String name)
    {
        super(name);
        
        try 
        { 
            Driver driver = new Driver();
        } 
        catch (Exception ex) 
        {;}
    }

    protected void setUp() throws Exception
    {
        conn = TestUtil.openDB();
        TestUtil.createTable(conn, "streamtable", "bin bytea, str text");
        TestUtil.createTable(conn, "texttable", "ch char(3), te text, vc varchar(3)");
        TestUtil.createTable(conn, "intervaltable", "i interval");
    }

    protected void tearDown() throws SQLException
    {
        TestUtil.dropTable(conn, "streamtable");
        TestUtil.dropTable(conn, "texttable");
        TestUtil.dropTable(conn, "intervaltable");
        TestUtil.closeDB(conn);
    }

    public void testSetBinaryStream() throws SQLException
    {
        ByteArrayInputStream bais;
        byte buf[] = new byte[10];
        for (int i = 0; i < buf.length; i++)
        {
            buf[i] = (byte)i;
        }

        bais = null;
        doSetBinaryStream(bais, 0);

        bais = new ByteArrayInputStream(new byte[0]);
        doSetBinaryStream(bais, 0);

        bais = new ByteArrayInputStream(buf);
        doSetBinaryStream(bais, 0);

        bais = new ByteArrayInputStream(buf);
        doSetBinaryStream(bais, 10);
    }

    public void testSetAsciiStream() throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, "ASCII"));
        pw.println("Hello");
        pw.flush();

        ByteArrayInputStream bais;

        bais = new ByteArrayInputStream(baos.toByteArray());
        doSetAsciiStream(bais, 0);

        bais = new ByteArrayInputStream(baos.toByteArray());
        doSetAsciiStream(bais, 6);

        bais = new ByteArrayInputStream(baos.toByteArray());
        doSetAsciiStream(bais, 100);
    }

    public void testExecuteStringOnPreparedStatement() throws Exception {
        PreparedStatement pstmt = conn.prepareStatement("SELECT 1");

        try
        {
            pstmt.executeQuery("SELECT 2");
            fail("Expected an exception when executing a new SQL query on a prepared statement");
        }
        catch (SQLException e)
        {
        }

        try
        {
            pstmt.executeUpdate("UPDATE streamtable SET bin=bin");
            fail("Expected an exception when executing a new SQL update on a prepared statement");
        }
        catch (SQLException e)
        {
        }

        try
        {
            pstmt.execute("UPDATE streamtable SET bin=bin");
            fail("Expected an exception when executing a new SQL statement on a prepared statement");
        }
        catch (SQLException e)
        {
        }
    }

    public void testBinaryStreamErrorsRestartable() throws SQLException {
        // The V2 protocol does not have the ability to recover when
        // streaming data to the server.  We could potentially try
        // introducing a syntax error to force the query to fail, but
        // that seems dangerous.
        //
        if (!TestUtil.isProtocolVersion(conn, 3))
        {
            return ;
        }

        byte buf[] = new byte[10];
        for (int i = 0; i < buf.length; i++)
        {
            buf[i] = (byte)i;
        }

        // InputStream is shorter than the length argument implies.
        InputStream is = new ByteArrayInputStream(buf);
        runBrokenStream(is, buf.length + 1);

        // InputStream throws an Exception during read.
        is = new BrokenInputStream(new ByteArrayInputStream(buf), buf.length / 2);
        runBrokenStream(is, buf.length);

        // Invalid length < 0.
        is = new ByteArrayInputStream(buf);
        runBrokenStream(is, -1);

        // Total Bind message length too long.
        is = new ByteArrayInputStream(buf);
        runBrokenStream(is, Integer.MAX_VALUE);
    }

    private void runBrokenStream(InputStream is, int length) throws SQLException
    {
        PreparedStatement pstmt = null;
        try
        {
            pstmt = conn.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
            pstmt.setBinaryStream(1, is, length);
            pstmt.setString(2, "Other");
            pstmt.executeUpdate();
            fail("This isn't supposed to work.");
        }
        catch (SQLException sqle)
        {
            // don't need to rollback because we're in autocommit mode
            pstmt.close();

            // verify the connection is still valid and the row didn't go in.
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM streamtable");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
            rs.close();
            stmt.close();
        }
    }

    private void doSetBinaryStream(ByteArrayInputStream bais, int length) throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
        pstmt.setBinaryStream(1, bais, length);
        pstmt.setString(2, null);
        pstmt.executeUpdate();
        pstmt.close();
    }

    private void doSetAsciiStream(InputStream is, int length) throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
        pstmt.setBytes(1, null);
        pstmt.setAsciiStream(2, is, length);
        pstmt.executeUpdate();
        pstmt.close();
    }

    public void testTrailingSpaces() throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO texttable (ch, te, vc) VALUES (?, ?, ?) ");
        String str = "a  ";
        pstmt.setString(1, str);
        pstmt.setString(2, str);
        pstmt.setString(3, str);
        pstmt.executeUpdate();
        pstmt.close();

        pstmt = conn.prepareStatement("SELECT ch, te, vc FROM texttable WHERE ch=? AND te=? AND vc=?");
        pstmt.setString(1, str);
        pstmt.setString(2, str);
        pstmt.setString(3, str);
        ResultSet rs = pstmt.executeQuery();
        assertTrue(rs.next());
        assertEquals(str, rs.getString(1));
        assertEquals(str, rs.getString(2));
        assertEquals(str, rs.getString(3));
        rs.close();
        pstmt.close();
    }

    public void testSetNull() throws SQLException {
        // valid: fully qualified type to setNull()
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO texttable (te) VALUES (?)");
        pstmt.setNull(1, Types.VARCHAR);
        pstmt.executeUpdate();

        // valid: fully qualified type to setObject()
        pstmt.setObject(1, null, Types.VARCHAR);
        pstmt.executeUpdate();

        // valid: setObject() with partial type info and a typed "null object instance"
        PGobject dummy = new PGobject();
        dummy.setType("text");
        dummy.setValue(null);
        pstmt.setObject(1, dummy, Types.OTHER);
        pstmt.executeUpdate();

        // setObject() with no type info
        pstmt.setObject(1, null);
        pstmt.executeUpdate();

        // setObject() with insufficient type info
        pstmt.setObject(1, null, Types.OTHER);
        pstmt.executeUpdate();

        // setNull() with insufficient type info
        pstmt.setNull(1, Types.OTHER);
        pstmt.executeUpdate();

        pstmt.close();
    }

    public void testSingleQuotes() throws SQLException {
        String[] testStrings = new String[] {
                                   "bare ? question mark",
                                   "quoted \\' single quote",
                                   "doubled '' single quote",
                                   "octal \\060 constant",
                                   "escaped \\? question mark",
                                   "double \\\\ backslash",
                                   "double \" quote",
                               };
        
        String[] testStringsStdConf = new String[] {
                                    "bare ? question mark",
                                    "quoted '' single quote",
                                    "doubled '' single quote",
                                    "octal 0 constant",
                                    "escaped ? question mark",
                                    "double \\ backslash",
                                    "double \" quote",
                                };

        String[] expected = new String[] {
                                "bare ? question mark",
                                "quoted ' single quote",
                                "doubled ' single quote",
                                "octal 0 constant",
                                "escaped ? question mark",
                                "double \\ backslash",
                                "double \" quote",
                            };

        if (! TestUtil.haveMinimumServerVersion(conn, "8.2"))
        {
            for (int i = 0; i < testStrings.length; ++i)
            {
                PreparedStatement pstmt = conn.prepareStatement("SELECT '" + testStrings[i] + "'");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                assertEquals(expected[i], rs.getString(1));
                rs.close();
                pstmt.close();
            }
        }
        else
        {
            boolean oldStdStrings = TestUtil.getStandardConformingStrings(conn);
            Statement stmt = conn.createStatement();

            // Test with standard_conforming_strings turned off.
            stmt.execute("SET standard_conforming_strings TO off");
            for (int i = 0; i < testStrings.length; ++i)
            {
                PreparedStatement pstmt = conn.prepareStatement("SELECT '" + testStrings[i] + "'");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                assertEquals(expected[i], rs.getString(1));
                rs.close();
                pstmt.close();
            }

            // Test with standard_conforming_strings turned off...
            // ... using the escape string syntax (E'').
            stmt.execute("SET standard_conforming_strings TO on");
            for (int i = 0; i < testStrings.length; ++i)
            {
                PreparedStatement pstmt = conn.prepareStatement("SELECT E'" + testStrings[i] + "'");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                assertEquals(expected[i], rs.getString(1));
                rs.close();
                pstmt.close();
            }
            // ... using standard conforming input strings.
            for (int i = 0; i < testStrings.length; ++i)
            {
                PreparedStatement pstmt = conn.prepareStatement("SELECT '" + testStringsStdConf[i] + "'");
                ResultSet rs = pstmt.executeQuery();
                assertTrue(rs.next());
                assertEquals(expected[i], rs.getString(1));
                rs.close();
                pstmt.close();
            }
            
            stmt.execute("SET standard_conforming_strings TO " + (oldStdStrings ? "on" : "off"));
            stmt.close();
        }
    }

    public void testDoubleQuotes() throws SQLException {
        String[] testStrings = new String[] {
                                   "bare ? question mark",
                                   "single ' quote",
                                   "doubled '' single quote",
                                   "doubled \"\" double quote",
                                   "no backslash interpretation here: \\",
                               };

        for (int i = 0; i < testStrings.length; ++i)
        {
            PreparedStatement pstmt = conn.prepareStatement("CREATE TABLE \"" + testStrings[i] + "\" (i integer)");
            pstmt.executeUpdate();
            pstmt.close();

            pstmt = conn.prepareStatement("DROP TABLE \"" + testStrings[i] + "\"");
            pstmt.executeUpdate();
            pstmt.close();
        }
    }
    
    public void testDollarQuotes() throws SQLException {
        // dollar-quotes are supported in the backend since version 8.0
        if (!TestUtil.haveMinimumServerVersion(conn, "8.0"))
            return;

        PreparedStatement st;
        ResultSet rs;
        
        st = conn.prepareStatement("SELECT $$;$$ WHERE $x$?$x$=$_0$?$_0$ AND $$?$$=?");
        st.setString(1, "?");
        rs = st.executeQuery();
        assertTrue(rs.next());
        assertEquals(";", rs.getString(1));
        assertFalse(rs.next());
        st.close();
        
        st = conn.prepareStatement(
                  "SELECT $__$;$__$ WHERE ''''=$q_1$'$q_1$ AND ';'=?;"
                + "SELECT $x$$a$;$x $a$$x$ WHERE $$;$$=? OR ''=$c$c$;$c$;"
                + "SELECT ?");
        st.setString(1, ";");
        st.setString(2, ";");
        st.setString(3, "$a$ $a$");
        
        assertTrue(st.execute());
        rs = st.getResultSet();
        assertTrue(rs.next());
        assertEquals(";", rs.getString(1));
        assertFalse(rs.next());
        
        assertTrue(st.getMoreResults());
        rs = st.getResultSet();
        assertTrue(rs.next());
        assertEquals("$a$;$x $a$", rs.getString(1));
        assertFalse(rs.next());
        
        assertTrue(st.getMoreResults());
        rs = st.getResultSet();
        assertTrue(rs.next());
        assertEquals("$a$ $a$", rs.getString(1));
        assertFalse(rs.next());
        st.close();
    }
    
    public void testDollarQuotesAndIdentifiers() throws SQLException {
        // dollar-quotes are supported in the backend since version 8.0
        if (!TestUtil.haveMinimumServerVersion(conn, "8.0"))
            return;
        
        PreparedStatement st;
        
        conn.createStatement().execute("CREATE TEMP TABLE a$b$c(a varchar, b varchar)");
        st = conn.prepareStatement("INSERT INTO a$b$c (a, b) VALUES (?, ?)");
        st.setString(1, "a");
        st.setString(2, "b");
        st.executeUpdate();
        st.close();

        conn.createStatement().execute("CREATE TEMP TABLE e$f$g(h varchar, e$f$g varchar) ");
        st = conn.prepareStatement("UPDATE e$f$g SET h = ? || e$f$g");
        st.setString(1, "a");
        st.executeUpdate();
        st.close();
    }
    
    public void testComments() throws SQLException {
        PreparedStatement st;
        ResultSet rs;

        st = conn.prepareStatement("SELECT /*?*/ /*/*/*/**/*/*/*/1;SELECT ?;--SELECT ?");
        st.setString(1, "a");
        assertTrue(st.execute());
        assertTrue(st.getMoreResults());
        assertFalse(st.getMoreResults());
        st.close();
        
        st = conn.prepareStatement("SELECT /**/'?'/*/**/*/ WHERE '?'=/*/*/*?*/*/*/--?\n?");
        st.setString(1, "?");
        rs = st.executeQuery();
        assertTrue(rs.next());
        assertEquals("?", rs.getString(1));
        assertFalse(rs.next());
        st.close();        
    }
    
    public void testDouble() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE TEMP TABLE double_tab (max_double float, min_double float, null_value float)");
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "insert into double_tab values (?,?,?)");
        pstmt.setDouble(1, 1.0E125);
        pstmt.setDouble(2, 1.0E-130);
        pstmt.setNull(3,Types.DOUBLE);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from double_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());
        double d = rs.getDouble(1);
        assertTrue( rs.getDouble(1) == 1.0E125 );
        assertTrue( rs.getDouble(2) == 1.0E-130 );
        rs.getDouble(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    
    public void testFloat() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE TEMP TABLE float_tab (max_float real, min_float real, null_value real)");
        pstmt.executeUpdate();
        pstmt.close();
       
        pstmt = conn.prepareStatement( "insert into float_tab values (?,?,?)");
        pstmt.setFloat(1,(float)1.0E37 );
        pstmt.setFloat(2, (float)1.0E-37);
        pstmt.setNull(3,Types.FLOAT);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from float_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());
        float f = rs.getFloat(1);
        assertTrue( "expected 1.0E37,received " + rs.getFloat(1), rs.getFloat(1) == (float)1.0E37 );
        assertTrue( "expected 1.0E-37,received " + rs.getFloat(2), rs.getFloat(2) == (float)1.0E-37 );
        rs.getDouble(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    
    public void testBoolean() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE TEMP TABLE bool_tab (max_val boolean, min_val boolean, null_val boolean)");
        pstmt.executeUpdate();
        pstmt.close();
       
        pstmt = conn.prepareStatement( "insert into bool_tab values (?,?,?)");
        pstmt.setBoolean(1,true );
        pstmt.setBoolean(2, false);
        pstmt.setNull(3,Types.BIT);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from bool_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected true,received " + rs.getBoolean(1), rs.getBoolean(1) == true );
        assertTrue( "expected false,received " + rs.getBoolean(2), rs.getBoolean(2) == false );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    
    public void testSetFloatInteger() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE float_tab (max_val float8, min_val float, null_val float8)");
        pstmt.executeUpdate();
        pstmt.close();
        
        Integer maxInteger= new Integer(2147483647), minInteger = new Integer(-2147483648);
        
        Double maxFloat=new Double( 2147483647), minFloat = new Double( -2147483648 );
        
        pstmt = conn.prepareStatement( "insert into float_tab values (?,?,?)");
        pstmt.setObject(1,maxInteger,Types.FLOAT );
        pstmt.setObject(2,minInteger,Types.FLOAT);
        pstmt.setNull(3,Types.FLOAT);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from float_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected "+maxFloat+" ,received " + rs.getObject(1), ((Double)rs.getObject(1)).equals(maxFloat) );
        assertTrue( "expected "+minFloat+" ,received " + rs.getObject(2), ((Double)rs.getObject(2)).equals( minFloat) );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    public void testSetFloatString() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
        pstmt.executeUpdate();
        pstmt.close();
        
        String maxStringFloat = new String("1.0E37"), minStringFloat = new String("1.0E-37");
        Double maxFloat=new Double(1.0E37), minFloat = new Double( 1.0E-37 );
        
        pstmt = conn.prepareStatement( "insert into float_tab values (?,?,?)");
        pstmt.setObject(1,maxStringFloat,Types.FLOAT );
        pstmt.setObject(2,minStringFloat,Types.FLOAT );
        pstmt.setNull(3,Types.FLOAT);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from float_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected true,received " + rs.getObject(1), ((Double)rs.getObject(1)).equals(maxFloat) );
        assertTrue( "expected false,received " + rs.getBoolean(2), ((Double)rs.getObject(2)).equals( minFloat) );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    
    public void testSetFloatBigDecimal() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE float_tab (max_val float8, min_val float8, null_val float8)");
        pstmt.executeUpdate();
        pstmt.close();
        
        BigDecimal maxBigDecimalFloat = new BigDecimal("1.0E37"), minBigDecimalFloat = new BigDecimal("1.0E-37");
        Double maxFloat=new Double(1.0E37), minFloat = new Double( 1.0E-37 );
        
        pstmt = conn.prepareStatement( "insert into float_tab values (?,?,?)");
        pstmt.setObject(1,maxBigDecimalFloat,Types.FLOAT );
        pstmt.setObject(2,minBigDecimalFloat,Types.FLOAT );
        pstmt.setNull(3,Types.FLOAT);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from float_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected " + maxFloat + " ,received " + rs.getObject(1), ((Double)rs.getObject(1)).equals(maxFloat) );
        assertTrue( "expected " + minFloat + " ,received " + rs.getObject(2), ((Double)rs.getObject(2)).equals( minFloat) );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    public void testSetTinyIntFloat() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE tiny_int (max_val int4, min_val int4, null_val int4)");
        pstmt.executeUpdate();
        pstmt.close();
        
        Integer maxInt = new Integer( 127 ), minInt = new Integer(-127);
        Float maxIntFloat = new Float( 127 ), minIntFloat = new Float( -127 ); 
        
        pstmt = conn.prepareStatement( "insert into tiny_int values (?,?,?)");
        pstmt.setObject(1,maxIntFloat,Types.TINYINT );
        pstmt.setObject(2,minIntFloat,Types.TINYINT );
        pstmt.setNull(3,Types.TINYINT);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from tiny_int");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected " + maxInt+" ,received " + rs.getObject(1), ((Integer)rs.getObject(1)).equals( maxInt ) );
        assertTrue( "expected " + minInt+" ,received " + rs.getObject(2), ((Integer)rs.getObject(2)).equals( minInt ) );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }   

    public void testSetSmallIntFloat() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE small_int (max_val int4, min_val int4, null_val int4)");
        pstmt.executeUpdate();
        pstmt.close();
        
        Integer maxInt = new Integer( 32767 ), minInt = new Integer(-32768);
        Float maxIntFloat = new Float( 32767 ), minIntFloat = new Float( -32768 ); 
        
        pstmt = conn.prepareStatement( "insert into small_int values (?,?,?)");
        pstmt.setObject(1,maxIntFloat,Types.SMALLINT );
        pstmt.setObject(2,minIntFloat,Types.SMALLINT );
        pstmt.setNull(3,Types.TINYINT);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from small_int");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected " + maxInt+" ,received " + rs.getObject(1), ((Integer)rs.getObject(1)).equals( maxInt ) );
        assertTrue( "expected " + minInt+" ,received " + rs.getObject(2), ((Integer)rs.getObject(2)).equals( minInt ) );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }   
    public void testSetIntFloat() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE int_TAB (max_val int4, min_val int4, null_val int4)");
        pstmt.executeUpdate();
        pstmt.close();
        
        Integer maxInt = new Integer( 1000 ), minInt = new Integer(-1000);
        Float maxIntFloat = new Float( 1000 ), minIntFloat = new Float( -1000 ); 
        
        pstmt = conn.prepareStatement( "insert into int_tab values (?,?,?)");
        pstmt.setObject(1,maxIntFloat,Types.INTEGER );
        pstmt.setObject(2,minIntFloat,Types.INTEGER );
        pstmt.setNull(3,Types.INTEGER);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from int_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected " + maxInt+" ,received " + rs.getObject(1), ((Integer)rs.getObject(1)).equals( maxInt ) );
        assertTrue( "expected " + minInt+" ,received " + rs.getObject(2), ((Integer)rs.getObject(2)).equals( minInt ) );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    public void testSetBooleanDouble() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE double_tab (max_val float, min_val float, null_val float)");
        pstmt.executeUpdate();
        pstmt.close();
        
        Boolean trueVal = Boolean.TRUE, falseVal = Boolean.FALSE;
        Double dBooleanTrue = new Double(1), dBooleanFalse = new Double( 0 ); 
        
        pstmt = conn.prepareStatement( "insert into double_tab values (?,?,?)");
        pstmt.setObject(1,trueVal,Types.DOUBLE );
        pstmt.setObject(2,falseVal,Types.DOUBLE );
        pstmt.setNull(3,Types.DOUBLE);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from double_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected " + dBooleanTrue + " ,received " + rs.getObject(1), ((Double)rs.getObject(1)).equals( dBooleanTrue ) );
        assertTrue( "expected " + dBooleanFalse + " ,received " + rs.getObject(2), ((Double)rs.getObject(2)).equals( dBooleanFalse ) );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    public void testSetBooleanNumeric() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE numeric_tab (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
        pstmt.executeUpdate();
        pstmt.close();
        
        Boolean trueVal = Boolean.TRUE, falseVal = Boolean.FALSE;
        BigDecimal dBooleanTrue = new BigDecimal(1), dBooleanFalse = new BigDecimal( 0 ); 
        
        pstmt = conn.prepareStatement( "insert into numeric_tab values (?,?,?)");
        pstmt.setObject(1,trueVal,Types.NUMERIC,2 );
        pstmt.setObject(2,falseVal,Types.NUMERIC,2 );
        pstmt.setNull(3,Types.DOUBLE);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from numeric_tab");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected " + dBooleanTrue + " ,received " + rs.getObject(1), ((BigDecimal)rs.getObject(1)).compareTo( dBooleanTrue )==0 );
        assertTrue( "expected " + dBooleanFalse + " ,received " + rs.getObject(2), ((BigDecimal)rs.getObject(2)).compareTo( dBooleanFalse )==0 );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }
    public void testSetBooleanDecimal() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("CREATE temp TABLE DECIMAL_TAB (max_val numeric(30,15), min_val numeric(30,15), null_val numeric(30,15))");
        pstmt.executeUpdate();
        pstmt.close();
        
        Boolean trueVal = Boolean.TRUE, falseVal = Boolean.FALSE;
        BigDecimal dBooleanTrue = new BigDecimal(1), dBooleanFalse = new BigDecimal( 0 ); 
        
        pstmt = conn.prepareStatement( "insert into DECIMAL_TAB values (?,?,?)");
        pstmt.setObject(1,trueVal,Types.DECIMAL,2 );
        pstmt.setObject(2,falseVal,Types.DECIMAL,2 );
        pstmt.setNull(3,Types.DOUBLE);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement( "select * from DECIMAL_TAB");
        ResultSet rs = pstmt.executeQuery();
        assertTrue( rs.next());

        assertTrue( "expected " + dBooleanTrue + " ,received " + rs.getObject(1), ((BigDecimal)rs.getObject(1)).compareTo( dBooleanTrue )==0 );
        assertTrue( "expected " + dBooleanFalse + " ,received " + rs.getObject(2), ((BigDecimal)rs.getObject(2)).compareTo( dBooleanFalse )==0 );
        rs.getFloat(3);
        assertTrue( rs.wasNull() );
        rs.close();
        pstmt.close();
        
    }

    public void testUnknownSetObject() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO intervaltable(i) VALUES (?)");

        if (TestUtil.isProtocolVersion(conn, 3))
        {
            pstmt.setString(1, "1 week");
            try {
                pstmt.executeUpdate();
                fail("Should have failed with type mismatch.");
            } catch (SQLException sqle) {
            }
        }

        pstmt.setObject(1, "1 week", Types.OTHER);
        pstmt.executeUpdate();
        pstmt.close();
    }

    /**
     * With autoboxing this apparently happens more often now.
     */
    public void testSetObjectCharacter() throws SQLException
    {
        PreparedStatement ps = conn.prepareStatement("INSERT INTO texttable(te) VALUES (?)");
        ps.setObject(1, new Character('z'));
        ps.executeUpdate();
        ps.close();
    }

    /**
     * When we have parameters of unknown type and it's not using
     * the unnamed statement, we issue a protocol level statment
     * describe message for the V3 protocol.  This test just makes
     * sure that works.
     */
    public void testStatementDescribe() throws SQLException
    {
        PreparedStatement pstmt = conn.prepareStatement("SELECT ?::int");
        pstmt.setObject(1, new Integer(2), Types.OTHER);
        for (int i=0; i<10; i++) {
            ResultSet rs = pstmt.executeQuery();
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            rs.close();
        }
        pstmt.close();
    }

}
