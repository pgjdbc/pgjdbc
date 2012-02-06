/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

/*
 * TestCase to test the internal functionality of
 * org.postgresql.jdbc2.DatabaseMetaData's various properties.
 * Methods which return a ResultSet are tested elsewhere.
 * This avoids a complicated setUp/tearDown for something like
 * assertTrue(dbmd.nullPlusNonNullIsNull());
 */

public class DatabaseMetaDataPropertiesTest extends TestCase
{

    private Connection con;
    /*
     * Constructor
     */
    public DatabaseMetaDataPropertiesTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
    }
    protected void tearDown() throws Exception
    {
        TestUtil.closeDB( con );
    }

    /*
     * The spec says this may return null, but we always do!
     */
    public void testGetMetaData() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);
    }

    /*
     * Test default capabilities
     */
    public void testCapabilities() throws SQLException
    {
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
        if (TestUtil.haveMinimumServerVersion(con, "7.3"))
            assertTrue(dbmd.supportsANSI92EntryLevelSQL());
        else
            assertTrue(!dbmd.supportsANSI92EntryLevelSQL());
        assertTrue(!dbmd.supportsANSI92IntermediateSQL());
        assertTrue(!dbmd.supportsANSI92FullSQL());

        assertTrue(dbmd.supportsIntegrityEnhancementFacility());

    }

    public void testJoins() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(dbmd.supportsOuterJoins());
        assertTrue(dbmd.supportsFullOuterJoins());
        assertTrue(dbmd.supportsLimitedOuterJoins());
    }

    public void testCursors() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(!dbmd.supportsPositionedDelete());
        assertTrue(!dbmd.supportsPositionedUpdate());
    }

    public void testValues() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);
        int indexMaxKeys = dbmd.getMaxColumnsInIndex();
        if (TestUtil.haveMinimumServerVersion(con, "7.3"))
        {
            assertEquals(32, indexMaxKeys);
        }
        else
        {
            assertEquals(16, indexMaxKeys);
        }
    }

    public void testNulls() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(!dbmd.nullsAreSortedAtStart());
        assertTrue( dbmd.nullsAreSortedAtEnd() != TestUtil.haveMinimumServerVersion(con, "7.2"));
        assertTrue( dbmd.nullsAreSortedHigh() == TestUtil.haveMinimumServerVersion(con, "7.2"));
        assertTrue(!dbmd.nullsAreSortedLow());

        assertTrue(dbmd.nullPlusNonNullIsNull());

        assertTrue(dbmd.supportsNonNullableColumns());
    }

    public void testLocalFiles() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(!dbmd.usesLocalFilePerTable());
        assertTrue(!dbmd.usesLocalFiles());
    }

    public void testIdentifiers() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(!dbmd.supportsMixedCaseIdentifiers()); // always false
        assertTrue(dbmd.supportsMixedCaseQuotedIdentifiers()); // always true

        assertTrue(!dbmd.storesUpperCaseIdentifiers());   // always false
        assertTrue(dbmd.storesLowerCaseIdentifiers());   // always true
        assertTrue(!dbmd.storesUpperCaseQuotedIdentifiers()); // always false
        assertTrue(!dbmd.storesLowerCaseQuotedIdentifiers()); // always false
        assertTrue(!dbmd.storesMixedCaseQuotedIdentifiers()); // always false

        assertTrue(dbmd.getIdentifierQuoteString().equals("\""));

    }

    public void testTables() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        // we can add columns
        assertTrue(dbmd.supportsAlterTableWithAddColumn());

        // we can only drop columns in >= 7.3
        if (TestUtil.haveMinimumServerVersion(con, "7.3"))
        {
            assertTrue(dbmd.supportsAlterTableWithDropColumn());
        }
        else
        {
            assertTrue(!dbmd.supportsAlterTableWithDropColumn());
        }
    }

    public void testSelect() throws SQLException
    {
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

    public void testDBParams() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(dbmd.getURL().equals(TestUtil.getURL()));
        assertTrue(dbmd.getUserName().equals(TestUtil.getUser()));
    }

    public void testDbProductDetails() throws SQLException
    {
        assertTrue(con instanceof org.postgresql.PGConnection);

        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(dbmd.getDatabaseProductName().equals("PostgreSQL"));
    }

    public void testDriverVersioning() throws SQLException
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertNotNull(dbmd);

        assertTrue(dbmd.getDriverVersion().equals(org.postgresql.Driver.getVersion()));
        assertTrue(dbmd.getDriverMajorVersion() == org.postgresql.Driver.MAJORVERSION);
        assertTrue(dbmd.getDriverMinorVersion() == org.postgresql.Driver.MINORVERSION);
    }
}

