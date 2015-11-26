/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.sql.Clob;
import java.sql.SQLException;

import org.postgresql.core.BaseConnection;

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
        Charset connectionCharset = Charset.forName(conn.getEncoding().name());
        return new InputStreamReader(getBinaryStream(), connectionCharset);
    }

    public synchronized String getSubString(long i, int j) throws SQLException
    {
        assertPosition(i, j);
        getLo(false).seek((int)i - 1);
        return new String(getLo(false).read(j));
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
