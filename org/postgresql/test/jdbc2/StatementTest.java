package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import java.util.Calendar;
import junit.framework.*;
import java.sql.*;
import java.util.HashMap;
/*
 * $PostgreSQL: $
 *
 * Test for getObject
 */

public class StatementTest extends TestCase
{
  Connection con = null;

  public StatementTest(String name )
  {
    super(name);
  }
  protected void setUp() throws Exception
  {
    super.setUp();

    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "test_statement",
        "i int");
    TestUtil.createTempTable(con, "escapetest",
                             "ts timestamp, d date, t time");
  }

  protected void tearDown() throws Exception
  {
    super.tearDown();
    TestUtil.dropTable( con, "test_statement" );
    con.close();
  }

  public void testClose() throws SQLException
  {
      Statement stmt = null;
      stmt = con.createStatement();
      stmt.close();

      try
      {
          stmt.getResultSet();
          this.fail( "statements should not be re-used after close" );
      }
      catch (SQLException ex)
      {

      }
  }

  /**
   * Closing a Statement twice is not an error.
   */
  public void testDoubleClose() throws SQLException
  {
     Statement stmt = con.createStatement();
     stmt.close();
     stmt.close();
  }

	public void testUpdateCount() throws SQLException
	{
		Statement stmt = con.createStatement();
		int count;

		count = stmt.executeUpdate("INSERT INTO test_statement VALUES (3)");
		assertEquals(1,count);
		count = stmt.executeUpdate("INSERT INTO test_statement VALUES (3)");
		assertEquals(1,count);

		count = stmt.executeUpdate("UPDATE test_statement SET i=4");
		assertEquals(2,count);

		count = stmt.executeUpdate("CREATE TEMP TABLE another_table (a int)");
		assertEquals(0,count);
	}
    public void testEscapeProcessing() throws SQLException
    {
        Statement stmt = con.createStatement();
        int count;

        count = stmt.executeUpdate("insert into escapetest (ts) values ({ts '1900-01-01 00:00:00'})");
        assertEquals(1,count);

        count = stmt.executeUpdate("insert into escapetest (d) values ({d '1900-01-01'})");
        assertEquals(1,count);

        count = stmt.executeUpdate("insert into escapetest (t) values ({t '00:00:00'})");
        assertEquals(1,count);


        ResultSet rs = stmt.executeQuery( "select {fn 'version()'} as version" );
        assertTrue(rs.next());

        count= stmt.executeUpdate( "create temp table b (i int)" );
        assertEquals(0,count);

        rs = stmt.executeQuery( "select * from test_statement as a {oj left outer join b on (a.i=b.i)} ");
        assertTrue(!rs.next());
    }
}
