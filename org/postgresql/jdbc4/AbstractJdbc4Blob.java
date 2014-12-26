/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;


import java.sql.*;

import org.postgresql.largeobject.LargeObject;

public abstract class AbstractJdbc4Blob extends org.postgresql.jdbc3.AbstractJdbc3Blob
{

    public AbstractJdbc4Blob(org.postgresql.core.BaseConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

    public synchronized java.io.InputStream getBinaryStream(long pos, long length) throws SQLException
    {
        checkFreed();
        LargeObject subLO = getLo(false).copy();
        addSubLO(subLO);
        if (pos > Integer.MAX_VALUE)
        {
            subLO.seek64(pos - 1, LargeObject.SEEK_SET);
        }
        else
        {
            subLO.seek((int) pos - 1, LargeObject.SEEK_SET);
        }
        return subLO.getInputStream(length);
    }

}

