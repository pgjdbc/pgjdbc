/*
 * Copyright (c) 2013, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

import org.junit.Test;

import java.sql.SQLException;
import java.sql.Statement;

/*
 * Test that enhanced error reports return the correct origin for constraint violation errors.
 */
public class ServerErrorTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeMinimumServerVersion(ServerVersion.v9_3);
    Statement stmt = con.createStatement();

    stmt.execute("CREATE DOMAIN testdom AS int4 CHECK (value < 10)");
    TestUtil.createTable(con, "testerr", "id int not null, val testdom not null");
    stmt.execute("ALTER TABLE testerr ADD CONSTRAINT testerr_pk PRIMARY KEY (id)");
    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testerr");
    Statement stmt = con.createStatement();
    stmt.execute("DROP DOMAIN IF EXISTS testdom");
    stmt.close();
    super.tearDown();
  }

  @Test
  public void testPrimaryKey() throws Exception {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO testerr (id, val) VALUES (1, 1)");
    try {
      stmt.executeUpdate("INSERT INTO testerr (id, val) VALUES (1, 1)");
      fail("Should have thrown a duplicate key exception.");
    } catch (SQLException sqle) {
      ServerErrorMessage err = ((PSQLException) sqle).getServerErrorMessage();
      assertEquals("public", err.getSchema());
      assertEquals("testerr", err.getTable());
      assertEquals("testerr_pk", err.getConstraint());
      assertEquals(PSQLState.UNIQUE_VIOLATION.getState(), err.getSQLState());
      assertNull(err.getDatatype());
      assertNull(err.getColumn());
    }
    stmt.close();
  }

  @Test
  public void testColumn() throws Exception {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("INSERT INTO testerr (id, val) VALUES (1, NULL)");
      fail("Should have thrown a not null constraint violation.");
    } catch (SQLException sqle) {
      ServerErrorMessage err = ((PSQLException) sqle).getServerErrorMessage();
      assertEquals("public", err.getSchema());
      assertEquals("testerr", err.getTable());
      assertEquals("val", err.getColumn());
      assertNull(err.getDatatype());
      assertNull(err.getConstraint());
    }
    stmt.close();
  }

  @Test
  public void testDatatype() throws Exception {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("INSERT INTO testerr (id, val) VALUES (1, 20)");
      fail("Should have thrown a constraint violation.");
    } catch (SQLException sqle) {
      ServerErrorMessage err = ((PSQLException) sqle).getServerErrorMessage();
      assertEquals("public", err.getSchema());
      assertEquals("testdom", err.getDatatype());
      assertEquals("testdom_check", err.getConstraint());
    }
    stmt.close();
  }

  @Test
  public void testNotNullConstraint() throws Exception {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("INSERT INTO testerr (val) VALUES (1)");
      fail("Should have thrown a not-null exception.");
    } catch (SQLException sqle) {
      ServerErrorMessage err = ((PSQLException) sqle).getServerErrorMessage();
      assertEquals("public", err.getSchema());
      assertEquals("testerr", err.getTable());
      assertEquals("id", err.getColumn());
      assertEquals(PSQLState.NOT_NULL_VIOLATION.getState(), err.getSQLState());
      assertNull(err.getDatatype());
    }
    stmt.close();
  }

  @Test
  public void testForeignKeyConstraint() throws Exception {
    TestUtil.createTable(con, "testerr_foreign", "id int not null, testerr_id int,"
        + "CONSTRAINT testerr FOREIGN KEY (testerr_id) references testerr(id)");
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO testerr (id, val) VALUES (1, 1)");
    try {
      stmt.executeUpdate("INSERT INTO testerr_foreign (id, testerr_id) VALUES (1, 2)");
      fail("Should have thrown a foreign key exception.");
    } catch (SQLException sqle) {
      ServerErrorMessage err = ((PSQLException) sqle).getServerErrorMessage();
      assertEquals("public", err.getSchema());
      assertEquals("testerr_foreign", err.getTable());
      assertEquals(PSQLState.FOREIGN_KEY_VIOLATION.getState(), err.getSQLState());
      assertNull(err.getDatatype());
      assertNull(err.getColumn());
    }
    TestUtil.dropTable(con, "testerr_foreign");
    stmt.close();
  }

  @Test
  public void testCheckConstraint() throws Exception {
    TestUtil.createTable(con, "testerr_check", "id int not null, max10 int CHECK (max10 < 11)");
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO testerr_check (id, max10) VALUES (1, 5)");
    try {
      stmt.executeUpdate("INSERT INTO testerr_check (id, max10) VALUES (2, 11)");
      fail("Should have thrown a check exception.");
    } catch (SQLException sqle) {
      ServerErrorMessage err = ((PSQLException) sqle).getServerErrorMessage();
      assertEquals("public", err.getSchema());
      assertEquals("testerr_check", err.getTable());
      assertEquals(PSQLState.CHECK_VIOLATION.getState(), err.getSQLState());
      assertNull(err.getDatatype());
      assertNull(err.getColumn());
    }
    TestUtil.dropTable(con, "testerr_check");
    stmt.close();
  }

  @Test
  public void testExclusionConstraint() throws Exception {
    TestUtil.createTable(con, "testerr_exclude", "id int, EXCLUDE (id WITH =)");
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO testerr_exclude (id) VALUES (1108)");
    try {
      stmt.executeUpdate("INSERT INTO testerr_exclude (id) VALUES (1108)");
      fail("Should have thrown an exclusion exception.");
    } catch (SQLException sqle) {
      ServerErrorMessage err = ((PSQLException) sqle).getServerErrorMessage();
      assertEquals("public", err.getSchema());
      assertEquals("testerr_exclude", err.getTable());
      assertEquals(PSQLState.EXCLUSION_VIOLATION.getState(), err.getSQLState());
      assertNull(err.getDatatype());
      assertNull(err.getColumn());
    }
    TestUtil.dropTable(con, "testerr_exclude");
    stmt.close();
  }

}
