/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2Clob.java,v 1.10 2007/02/19 17:21:12 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


import org.postgresql.PGConnection;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Clob;
import java.sql.SQLException;

public class AbstractJdbc2Clob extends AbstractJdbc2BlobClob
{

    public AbstractJdbc2Clob(PGConnection conn, long oid) throws SQLException
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
