/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
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

    assertTrue(!dbmd.nullsAreSortedAtStart());
    assertTrue(dbmd.nullsAreSortedAtEnd() != true);
    assertTrue(dbmd.nullsAreSortedHigh() == true);
    assertTrue(!dbmd.nullsAreSortedLow());

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

    assertTrue(!dbmd.supportsMixedCaseIdentifiers()); // always false
    assertTrue(dbmd.supportsMixedCaseQuotedIdentifiers()); // always true

    assertTrue(!dbmd.storesUpperCaseIdentifiers()); // always false
    assertTrue(dbmd.storesLowerCaseIdentifiers()); // always true
    assertTrue(!dbmd.storesUpperCaseQuotedIdentifiers()); // always false
    assertTrue(!dbmd.storesLowerCaseQuotedIdentifiers()); // always false
    assertTrue(!dbmd.storesMixedCaseQuotedIdentifiers()); // always false

    assertTrue(dbmd.getIdentifierQuoteString().equals("\""));

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

    assertTrue(dbmd.getURL().equals(TestUtil.getURL()));
    assertTrue(dbmd.getUserName().equals(TestUtil.getUser()));
  }

  @Test
  public void testDbProductDetails() throws SQLException {
    assertTrue(con instanceof org.postgresql.PGConnection);

    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(dbmd.getDatabaseProductName().equals("PostgreSQL"));
    assertTrue(dbmd.getDatabaseMajorVersion() >= 8);
    assertTrue(dbmd.getDatabaseMinorVersion() >= 0);
    assertTrue(dbmd.getDatabaseProductVersion().startsWith(String.valueOf(dbmd.getDatabaseMajorVersion())));
  }

  @Test
  public void testDriverVersioning() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(dbmd.getDriverName().equals("PostgreSQL JDBC Driver"));
    assertTrue(dbmd.getDriverVersion().equals(org.postgresql.util.DriverInfo.DRIVER_VERSION));
    assertTrue(dbmd.getDriverMajorVersion() == new org.postgresql.Driver().getMajorVersion());
    assertTrue(dbmd.getDriverMinorVersion() == new org.postgresql.Driver().getMinorVersion());
    assertTrue(dbmd.getJDBCMajorVersion() >= 4);
    assertTrue(dbmd.getJDBCMinorVersion() >= 0);
  }
}
