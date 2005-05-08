/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2Blob.java,v 1.7.2.1 2005/02/15 08:55:50 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.SQLException;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLException;

public abstract class AbstractJdbc2Blob
{
    private LargeObject lo;

    public AbstractJdbc2Blob(PGConnection conn, int oid) throws SQLException
    {
        LargeObjectManager lom = conn.getLargeObjectAPI();
        this.lo = lom.open(oid);
    }

    public long length() throws SQLException
    {
        return lo.size();
    }

    public InputStream getBinaryStream() throws SQLException
    {
        return lo.getInputStream();
    }

    public byte[] getBytes(long pos, int length) throws SQLException
    {
        if (pos < 1) {
            throw new PSQLException(GT.tr("LOB positioning offsets start at 1."), PSQLState.INVALID_PARAMETER_VALUE);
        }
        lo.seek((int)(pos-1), LargeObject.SEEK_SET);
        return lo.read(length);
    }

    /*
     * For now, this is not implemented.
     */
    public long position(byte[] pattern, long start) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "position(byte[],long)");
    }

    /*
     * This should be simply passing the byte value of the pattern Blob
     */
    public long position(Blob pattern, long start) throws SQLException
    {
        return position(pattern.getBytes(1, (int)pattern.length()), start);
    }

}
