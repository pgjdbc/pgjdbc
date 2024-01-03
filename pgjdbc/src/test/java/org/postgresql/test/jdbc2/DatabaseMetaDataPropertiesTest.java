/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.Driver;
import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.util.DriverInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/*
* TestCase to test the internal functionality of org.postgresql.jdbc2.DatabaseMetaData's various
* properties. Methods which return a ResultSet are tested elsewhere. This avoids a complicated
* setUp/tearDown for something like assertTrue(dbmd.nullPlusNonNullIsNull());
*/
class DatabaseMetaDataPropertiesTest {
  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  /*
   * The spec says this may return null, but we always do!
   */
  @Test
  void getMetaData() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
  }

  /*
   * Test default capabilities
   */
  @Test
  void capabilities() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(dbmd.allProceduresAreCallable());
    assertTrue(dbmd.allTablesAreSelectable()); // not true all the time

    // This should always be false for postgresql (at least for 7.x)
    assertFalse(dbmd.isReadOnly());

    // we support multiple resultsets via multiple statements in one execute() now
    assertTrue(dbmd.supportsMultipleResultSets());

    // yes, as multiple backends can have transactions open
    assertTrue(dbmd.supportsMultipleTransactions());

    assertTrue(dbmd.supportsMinimumSQLGrammar());
    assertFalse(dbmd.supportsCoreSQLGrammar());
    assertFalse(dbmd.supportsExtendedSQLGrammar());
    assertTrue(dbmd.supportsANSI92EntryLevelSQL());
    assertFalse(dbmd.supportsANSI92IntermediateSQL());
    assertFalse(dbmd.supportsANSI92FullSQL());

    assertTrue(dbmd.supportsIntegrityEnhancementFacility());

  }

  @Test
  void joins() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertTrue(dbmd.supportsOuterJoins());
    assertTrue(dbmd.supportsFullOuterJoins());
    assertTrue(dbmd.supportsLimitedOuterJoins());
  }

  @Test
  void cursors() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertFalse(dbmd.supportsPositionedDelete());
    assertFalse(dbmd.supportsPositionedUpdate());
  }

  @Test
  void values() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);
    int indexMaxKeys = dbmd.getMaxColumnsInIndex();
    assertEquals(32, indexMaxKeys);
  }

  @Test
  void nulls() throws SQLException {
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
  void localFiles() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertFalse(dbmd.usesLocalFilePerTable());
    assertFalse(dbmd.usesLocalFiles());
  }

  @Test
  void identifiers() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertFalse(dbmd.supportsMixedCaseIdentifiers());
    assertTrue(dbmd.supportsMixedCaseQuotedIdentifiers());

    assertFalse(dbmd.storesUpperCaseIdentifiers());
    assertTrue(dbmd.storesLowerCaseIdentifiers());
    assertFalse(dbmd.storesUpperCaseQuotedIdentifiers());
    assertFalse(dbmd.storesLowerCaseQuotedIdentifiers());
    assertFalse(dbmd.storesMixedCaseQuotedIdentifiers());

    assertEquals("\"", dbmd.getIdentifierQuoteString());

  }

  @Test
  void tables() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    // we can add columns
    assertTrue(dbmd.supportsAlterTableWithAddColumn());

    // we can only drop columns in >= 7.3
    assertTrue(dbmd.supportsAlterTableWithDropColumn());
  }

  @Test
  void select() throws SQLException {
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
  void dBParams() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertEquals(TestUtil.getURL(), dbmd.getURL());
    assertEquals(TestUtil.getUser(), dbmd.getUserName());
  }

  @Test
  void dbProductDetails() throws SQLException {
    assertTrue(con instanceof PGConnection);

    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertEquals("PostgreSQL", dbmd.getDatabaseProductName());
    assertTrue(dbmd.getDatabaseMajorVersion() >= 8);
    assertTrue(dbmd.getDatabaseMinorVersion() >= 0);
    assertTrue(dbmd.getDatabaseProductVersion().startsWith(String.valueOf(dbmd.getDatabaseMajorVersion())));
  }

  @Test
  void driverVersioning() throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertNotNull(dbmd);

    assertEquals("PostgreSQL JDBC Driver", dbmd.getDriverName());
    assertEquals(DriverInfo.DRIVER_VERSION, dbmd.getDriverVersion());
    assertEquals(new Driver().getMajorVersion(), dbmd.getDriverMajorVersion());
    assertEquals(new Driver().getMinorVersion(), dbmd.getDriverMinorVersion());
    assertTrue(dbmd.getJDBCMajorVersion() >= 4);
    assertTrue(dbmd.getJDBCMinorVersion() >= 0);
  }
}
