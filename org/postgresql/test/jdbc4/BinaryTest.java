package org.postgresql.test.jdbc4;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.postgresql.PGConnection;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.PGStatement;
import org.postgresql.core.Field;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

/**
 * We don't want to use the binary protocol for one-off queries as it involves
 * another round-trip to the server to 'describe' the query. If we use the query
 * enough times (see {@link PGConnection#setPrepareThreshold(int)} then we'll
 * change to using the binary protocol to save bandwidth and reduce decoding
 * time.
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
        assertEquals(Field.BINARY_FORMAT, getFormat(results));

        results = statement.executeQuery();
        assertEquals(Field.BINARY_FORMAT, getFormat(results));
    }

    public void testPreparedStatement_1() throws Exception {
        ((PGStatement) statement).setPrepareThreshold(1);

        results = statement.executeQuery();
        assertEquals(Field.BINARY_FORMAT, getFormat(results));

        results = statement.executeQuery();
        assertEquals(Field.BINARY_FORMAT, getFormat(results));

        results = statement.executeQuery();
        assertEquals(Field.BINARY_FORMAT, getFormat(results));

        results = statement.executeQuery();
        assertEquals(Field.BINARY_FORMAT, getFormat(results));
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
    }

    private int getFormat(ResultSet results) throws SQLException {
        return ((PGResultSetMetaData) results.getMetaData()).getFormat(1);
    }
}
