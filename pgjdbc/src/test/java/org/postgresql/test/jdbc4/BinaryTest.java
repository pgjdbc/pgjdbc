package org.postgresql.test.jdbc4;

import org.postgresql.PGConnection;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.PGStatement;
import org.postgresql.core.Field;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * We don't want to use the binary protocol for one-off queries as it involves another round-trip to
 * the server to 'describe' the query. If we use the query enough times (see {@link
 * PGConnection#setPrepareThreshold(int)} then we'll change to using the binary protocol to save
 * bandwidth and reduce decoding time.
 */
public class BinaryTest extends TestCase {
  private Connection connection;
  private ResultSet results;
  private PreparedStatement statement;

  public BinaryTest(String name) {
    super(name);
  }

  @Override
  protected void setUp() throws Exception {
    connection = TestUtil.openDB();
    statement = connection.prepareStatement("select 1");

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  @Override
  protected void tearDown() throws Exception {
    TestUtil.closeDB(connection);
  }

  public void testPreparedStatement_3() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(3);

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  public void testPreparedStatement_1() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(1);

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  public void testPreparedStatement_0() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(0);

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  public void testPreparedStatement_negative1() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(-1);

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  public void testReceiveBinary() throws Exception {
    PreparedStatement ps = connection.prepareStatement("select ?");
    for (int i = 0; i < 10; i++) {
      ps.setInt(1, 42 + i);
      ResultSet rs = ps.executeQuery();
      assertEquals("One row should be returned", true, rs.next());
      assertEquals(42 + i, rs.getInt(1));
      rs.close();
    }
    ps.close();
  }

  private int getFormat(ResultSet results) throws SQLException {
    return ((PGResultSetMetaData) results.getMetaData()).getFormat(1);
  }
}
