/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2Clob.java,v 1.6 2004/11/09 08:48:29 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;


import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Clob;
import java.sql.SQLException;

public class AbstractJdbc2Clob
{
    private LargeObject lo;

    public AbstractJdbc2Clob(PGConnection conn, int oid) throws SQLException
    {
        LargeObjectManager lom = conn.getLargeObjectAPI();
        this.lo = lom.open(oid);
    }

    public long length() throws SQLException
    {
        return lo.size();
    }

    public InputStream getAsciiStream() throws SQLException
    {
        return lo.getInputStream();
    }

    public Reader getCharacterStream() throws SQLException
    {
        return new InputStreamReader(lo.getInputStream());
    }

    public String getSubString(long i, int j) throws SQLException
    {
        lo.seek((int)i - 1);
        return new String(lo.read(j));
    }

    /*
     * For now, this is not implemented.
     */
    public long position(String pattern, long start) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented();
    }

    /*
     * This should be simply passing the byte value of the pattern Blob
     */
    public long position(Clob pattern, long start) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented();
    }

}
