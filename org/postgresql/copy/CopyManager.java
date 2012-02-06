/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

/**
 * Bulk data copy for PostgreSQL
 */
package org.postgresql.copy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.sql.SQLException;

import org.postgresql.core.Encoding;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.BaseConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * API for PostgreSQL COPY bulk data transfer
 */
public class CopyManager {
    // I don't know what the best buffer size is, so we let people specify it if
    // they want, and if they don't know, we don't make them guess, so that if we
    // do figure it out we can just set it here and they reap the rewards.
    // Note that this is currently being used for both a number of bytes and a number
    // of characters.
    final static int DEFAULT_BUFFER_SIZE = 65536;

    private final Encoding encoding;
    private final QueryExecutor queryExecutor;
    private final BaseConnection connection;

    public CopyManager(BaseConnection connection) throws SQLException {
        this.encoding = connection.getEncoding();
        this.queryExecutor = connection.getQueryExecutor();
        this.connection = connection;
    }

    public CopyIn copyIn(String sql) throws SQLException {
        CopyOperation op = null;
        try {
            op = queryExecutor.startCopy(sql, connection.getAutoCommit());
            return (CopyIn) op;
        } catch(ClassCastException cce) {
            op.cancelCopy();
            throw new PSQLException(GT.tr("Requested CopyIn but got {0}", op.getClass().getName()), PSQLState.WRONG_OBJECT_TYPE, cce);
        }
    }

    public CopyOut copyOut(String sql) throws SQLException {
        CopyOperation op = null;
        try {
            op = queryExecutor.startCopy(sql, connection.getAutoCommit());
            return (CopyOut) op;
        } catch(ClassCastException cce) {
            op.cancelCopy();
            throw new PSQLException(GT.tr("Requested CopyOut but got {0}", op.getClass().getName()), PSQLState.WRONG_OBJECT_TYPE, cce);
        }
    }

    /**
     * Pass results of a COPY TO STDOUT query from database into a Writer.
     * @param sql COPY TO STDOUT statement
     * @param to the stream to write the results to (row by row)
     * @return number of rows updated for server 8.2 or newer; -1 for older
     * @throws SQLException on database usage errors
     * @throws IOException upon writer or database connection failure
     */
    public long copyOut(final String sql, Writer to) throws SQLException, IOException {
        byte[] buf;
        CopyOut cp = copyOut(sql);
        try {
            while ( (buf = cp.readFromCopy()) != null ) {
                to.write(encoding.decode(buf));
            }
            return cp.getHandledRowCount();
        } finally { // see to it that we do not leave the connection locked
            if(cp.isActive())
                cp.cancelCopy();
        }
    }

    /**
     * Pass results of a COPY TO STDOUT query from database into an OutputStream.
     * @param sql COPY TO STDOUT statement
     * @param to the stream to write the results to (row by row)
     * @return number of rows updated for server 8.2 or newer; -1 for older
     * @throws SQLException on database usage errors
     * @throws IOException upon output stream or database connection failure
     */
    public long copyOut(final String sql, OutputStream to) throws SQLException, IOException {
        byte[] buf;
        CopyOut cp = copyOut(sql);
        try {
            while( (buf = cp.readFromCopy()) != null ) {
                to.write(buf);
            }
            return cp.getHandledRowCount();
        } finally { // see to it that we do not leave the connection locked
            if(cp.isActive())
                cp.cancelCopy();
        }
    }

    /**
     * Use COPY FROM STDIN for very fast copying from a Reader into a database table.
     * @param sql COPY FROM STDIN statement
     * @param from a CSV file or such
     * @return number of rows updated for server 8.2 or newer; -1 for older
     * @throws SQLException on database usage issues
     * @throws IOException upon reader or database connection failure
     */
    public long copyIn(final String sql, Reader from) throws SQLException, IOException {
        return copyIn(sql, from, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Use COPY FROM STDIN for very fast copying from a Reader into a database table.
     * @param sql COPY FROM STDIN statement
     * @param from a CSV file or such
     * @param bufferSize number of characters to buffer and push over network to server at once
     * @return number of rows updated for server 8.2 or newer; -1 for older
     * @throws SQLException on database usage issues
     * @throws IOException upon reader or database connection failure
     */
    public long copyIn(final String sql, Reader from, int bufferSize) throws SQLException, IOException {
        char[] cbuf = new char[bufferSize];
        int len;
        CopyIn cp = copyIn(sql);
        try {
            while ( (len = from.read(cbuf)) > 0) {
                byte[] buf = encoding.encode(new String(cbuf, 0, len));
                cp.writeToCopy(buf, 0, buf.length);
            }
            return cp.endCopy();
        } finally { // see to it that we do not leave the connection locked
            if(cp.isActive())
                cp.cancelCopy();
        }
    }

    /**
     * Use COPY FROM STDIN for very fast copying from an InputStream into a database table.
     * @param sql COPY FROM STDIN statement
     * @param from a CSV file or such
     * @return number of rows updated for server 8.2 or newer; -1 for older
     * @throws SQLException on database usage issues
     * @throws IOException upon input stream or database connection failure
     */
    public long copyIn(final String sql, InputStream from) throws SQLException, IOException {
        return copyIn(sql, from, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Use COPY FROM STDIN for very fast copying from an InputStream into a database table.
     * @param sql COPY FROM STDIN statement
     * @param from a CSV file or such
     * @param bufferSize number of bytes to buffer and push over network to server at once
     * @return number of rows updated for server 8.2 or newer; -1 for older
     * @throws SQLException on database usage issues
     * @throws IOException upon input stream or database connection failure
     */
    public long copyIn(final String sql, InputStream from, int bufferSize) throws SQLException, IOException {
        byte[] buf = new byte[bufferSize];
        int len;
        CopyIn cp = copyIn(sql);
        try {
            while( (len = from.read(buf)) > 0 ) {
                cp.writeToCopy(buf, 0, len);
            }
            return cp.endCopy();
        } finally { // see to it that we do not leave the connection locked
            if(cp.isActive())
                cp.cancelCopy();
        }
    }
}
