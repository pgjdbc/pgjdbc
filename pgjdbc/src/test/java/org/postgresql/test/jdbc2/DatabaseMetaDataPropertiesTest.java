/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/*
 * TestCase to test the internal functionality of org.postgresql.jdbc2.DatabaseMetaData's various
 * properties. Methods which return a ResultSet are tested elsewhere. This avoids a complicated
 * setUp/tearDown for something like assertTrue(dbmd.nullPlusNonNullIsNull());
 */
public class DatabaseMetaDataPropertiesTest {
  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  /*
   * The spec says this may return null, but we always do!
   */
  @Test
  public void testGetMetaData() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
  }

  /*
   * Test default capabilities
   */
  @Test
  public void testCapabilities() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(dbmd.allProceduresAreCallable());
    assertTrue(dbmd.allTablesAreSelectable()); // not true all the time

    // This should always be false for postgresql (at least for 7.x)
    assertTrue(!dbmd.isReadOnly());

    // we support multiple resultsets via multiple statements in one execute() now
    assertTrue(dbmd.supportsMultipleResultSets());

    // yes, as multiple backends can have transactions open
    assertTrue(dbmd.supportsMultipleTransactions());

    assertTrue(dbmd.supportsMinimumSQLGrammar());
    assertTrue(!dbmd.supportsCoreSQLGrammar());
    assertTrue(!dbmd.supportsExtendedSQLGrammar());
    assertTrue(dbmd.supportsANSI92EntryLevelSQL());
    assertTrue(!dbmd.supportsANSI92IntermediateSQL());
    assertTrue(!dbmd.supportsANSI92FullSQL());

    assertTrue(dbmd.supportsIntegrityEnhancementFacility());

  }

  @Test
  public void testJoins() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(dbmd.supportsOuterJoins());
    assertTrue(dbmd.supportsFullOuterJoins());
    assertTrue(dbmd.supportsLimitedOuterJoins());
  }

  @Test
  public void testCursors() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(!dbmd.supportsPositionedDelete());
    assertTrue(!dbmd.supportsPositionedUpdate());
  }

  @Test
  public void testValues() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    int indexMaxKeys = dbmd.getMaxColumnsInIndex();
    assertEquals(32, indexMaxKeys);
  }

  @Test
  public void testNulls() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertFalse(dbmd.nullsAreSortedAtStart());
    assertFalse(dbmd.nullsAreSortedAtEnd());
    assertTrue(dbmd.nullsAreSortedHigh());
    assertFalse(dbmd.nullsAreSortedLow());

    assertTrue(dbmd.nullPlusNonNullIsNull());

    assertTrue(dbmd.supportsNonNullableColumns());
  }

  @Test
  public void testLocalFiles() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(!dbmd.usesLocalFilePerTable());
    assertTrue(!dbmd.usesLocalFiles());
  }

  @Test
  public void testIdentifiers() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertFalse(dbmd.supportsMixedCaseIdentifiers());
    assertTrue(dbmd.supportsMixedCaseQuotedIdentifiers());

    assertFalse(dbmd.storesUpperCaseIdentifiers());
    assertTrue(dbmd.storesLowerCaseIdentifiers());
    assertFalse(dbmd.storesUpperCaseQuotedIdentifiers());
    assertFalse(dbmd.storesLowerCaseQuotedIdentifiers());
    assertFalse(dbmd.storesMixedCaseQuotedIdentifiers());

    assertEquals( "\"", dbmd.getIdentifierQuoteString());

  }

  @Test
  public void testTables() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    // we can add columns
    assertTrue(dbmd.supportsAlterTableWithAddColumn());

    // we can only drop columns in >= 7.3
    assertTrue(dbmd.supportsAlterTableWithDropColumn());
  }

  @Test
  public void testSelect() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    // yes we can?: SELECT col a FROM a;
    assertTrue(dbmd.supportsColumnAliasing());

    // yes we can have expressions in ORDERBY
    assertTrue(dbmd.supportsExpressionsInOrderBy());

    // Yes, an ORDER BY clause can contain columns that are not in the
    // SELECT clause.
    assertTrue(dbmd.supportsOrderByUnrelated());

    assertTrue(dbmd.supportsGroupBy());
    assertTrue(dbmd.supportsGroupByUnrelated());
    assertTrue(dbmd.supportsGroupByBeyondSelect()); // needs checking
  }

  @Test
  public void testDBParams() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertEquals(TestUtil.getURL(), dbmd.getURL());
    assertEquals(TestUtil.getUser(), dbmd.getUserName());
  }

  @Test
  public void testDbProductDetails() throws SQLException {
    assertTrue(con instanceof org.postgresql.PGConnection);

    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertEquals("PostgreSQL", dbmd.getDatabaseProductName());
    assertTrue(dbmd.getDatabaseMajorVersion() >= 8);
    assertTrue(dbmd.getDatabaseMinorVersion() >= 0);
    assertTrue(dbmd.getDatabaseProductVersion().startsWith(String.valueOf(dbmd.getDatabaseMajorVersion())));
  }

  @Test
  public void testDriverVersioning() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertEquals("PostgreSQL JDBC Driver", dbmd.getDriverName());
    assertEquals(org.postgresql.util.DriverInfo.DRIVER_VERSION, dbmd.getDriverVersion());
    assertEquals(new org.postgresql.Driver().getMajorVersion(), dbmd.getDriverMajorVersion());
    assertEquals(new org.postgresql.Driver().getMinorVersion(), dbmd.getDriverMinorVersion());
    assertTrue(dbmd.getJDBCMajorVersion() >= 4);
    assertTrue(dbmd.getJDBCMinorVersion() >= 0);
  }
}
