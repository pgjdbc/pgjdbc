/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2Blob.java,v 1.8 2005/02/15 08:56:25 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.sql.SQLException;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;

public abstract class AbstractJdbc2Blob extends AbstractJdbc2BlobClob
{
    private LargeObject lo;

    public AbstractJdbc2Blob(PGConnection conn, int oid) throws SQLException
    {
        super(conn, oid);
    }

}
