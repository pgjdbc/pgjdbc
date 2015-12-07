/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc;

import org.postgresql.largeobject.LargeObject;

import java.sql.SQLException;

public class PgBlob extends AbstractBlobClob implements java.sql.Blob {

    public PgBlob(org.postgresql.core.BaseConnection conn, long oid) throws SQLException {
        super(conn, oid);
    }

    public synchronized java.io.InputStream getBinaryStream(long pos, long length) throws SQLException {
        checkFreed();
        LargeObject subLO = getLo(false).copy();
        addSubLO(subLO);
        if (pos > Integer.MAX_VALUE) {
            subLO.seek64(pos - 1, LargeObject.SEEK_SET);
        } else {
            subLO.seek((int) pos - 1, LargeObject.SEEK_SET);
        }
        return subLO.getInputStream(length);
    }

    /**
     * Writes the given array of bytes to the <code>BLOB</code> value that
     * this <code>Blob</code> object represents, starting at position
     * <code>pos</code>, and returns the number of bytes written.
     *
     * @param pos   the position in the <code>BLOB</code> object at which
     *              to start writing
     * @param bytes the array of bytes to be written to the <code>BLOB</code>
     *              value that this <code>Blob</code> object represents
     * @return the number of bytes written
     * @throws SQLException if there is an error accessing the
     *                      <code>BLOB</code> value
     * @see #getBytes
     * @since 1.4
     */
    public synchronized int setBytes(long pos, byte[] bytes) throws SQLException {
        return setBytes(pos, bytes, 0, bytes.length);
    }

    /**
     * Writes all or part of the given <code>byte</code> array to the
     * <code>BLOB</code> value that this <code>Blob</code> object represents
     * and returns the number of bytes written.
     * Writing starts at position <code>pos</code> in the <code>BLOB</code>
     * value; <code>len</code> bytes from the given byte array are written.
     *
     * @param pos    the position in the <code>BLOB</code> object at which
     *               to start writing
     * @param bytes  the array of bytes to be written to this <code>BLOB</code>
     *               object
     * @param offset the offset into the array <code>bytes</code> at which
     *               to start reading the bytes to be set
     * @param len    the number of bytes to be written to the <code>BLOB</code>
     *               value from the array of bytes <code>bytes</code>
     * @return the number of bytes written
     * @throws SQLException if there is an error accessing the
     *                      <code>BLOB</code> value
     * @see #getBytes
     * @since 1.4
     */
    public synchronized int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        assertPosition(pos);
        getLo(true).seek((int) (pos - 1));
        getLo(true).write(bytes, offset, len);
        return len;
    }
}
