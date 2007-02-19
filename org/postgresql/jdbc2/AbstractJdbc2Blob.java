/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2Blob.java,v 1.11 2007/02/19 06:00:24 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.sql.SQLException;

import org.postgresql.PGConnection;

public abstract class AbstractJdbc2Blob extends AbstractJdbc2BlobClob
{

    public AbstractJdbc2Blob(PGConnection conn, long oid) throws SQLException
    {
        super(conn, oid);
    }

}
