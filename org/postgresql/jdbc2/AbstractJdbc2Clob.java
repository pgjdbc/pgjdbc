/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


import org.postgresql.core.BaseConnection;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Clob;
import java.sql.SQLException;

public abstract class AbstractJdbc2Clob extends AbstractJdbc2BlobClob
{

    public AbstractJdbc2Clob(BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

    public synchronized InputStream getAsciiStream() throws SQLException
    {
        return getBinaryStream();
    }

    public synchronized Reader getCharacterStream() throws SQLException
    {
        return new InputStreamReader(getBinaryStream());
    }

    public synchronized String getSubString(long i, int j) throws SQLException
    {
        assertPosition(i, j);
        lo.seek((int)i - 1);
        return new String(lo.read(j));
    }

    /*
     * For now, this is not implemented.
     */
    public synchronized long position(String pattern, long start) throws SQLException
    {
        checkFreed();
        throw org.postgresql.Driver.notImplemented(this.getClass(), "position(String,long)");
    }

    /*
     * This should be simply passing the byte value of the pattern Blob
     */
    public synchronized long position(Clob pattern, long start) throws SQLException
    {
        checkFreed();
        throw org.postgresql.Driver.notImplemented(this.getClass(), "position(Clob,start)");
    }

}
