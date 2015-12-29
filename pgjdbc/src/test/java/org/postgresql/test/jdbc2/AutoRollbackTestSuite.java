package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class AutoRollbackTestSuite extends BaseTest {
  public AutoRollbackTestSuite(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "rollbacktest", "a int, str text");
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(con, "rollbacktest");
    super.tearDown();
  }

  public void testTransactionWithErrorStatement() throws SQLException {
    con.setAutoCommit(false);
    Statement statement = con.createStatement();
    statement.executeUpdate("insert into rollbacktest(a, str) values (0, 'test')");

    try {
      statement.execute("select 1/0");
    } catch (SQLException e) {
            /* ignore division by zero */
    }

    statement.close();
    {
      PreparedStatement ps = con.prepareStatement("insert into rollbacktest(a, str) values (?, ?)");
      for (int i = 1; i <= 5; i++) {
        ps.setInt(1, i);
        ps.setString(2, "s" + i);
        ps.executeUpdate();
      }
      ps.close();
    }
    {
      PreparedStatement ps = con.prepareStatement("select count(*) from rollbacktest where a = ?");
      for (int i = 0; i <= 7; i++) {
        ps.setInt(1, i);
        ResultSet rs = ps.executeQuery();
        assertTrue("select count(*) from rollbacktest should always return 1 row", rs.next());
        System.out.println(i + ": rs.getInt(1) = " + rs.getInt(1));
        assertEquals(i <= 5 ? 1 : 0, rs.getInt(1));
        rs.close();
      }
      ps.close();
    }
    con.commit();
  }
}
