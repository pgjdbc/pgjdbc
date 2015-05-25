/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.SQLException;

import org.postgresql.jdbc4.AbstractJdbc4DatabaseMetaData;

public abstract class AbstractJdbc42DatabaseMetaData extends AbstractJdbc4DatabaseMetaData
{
    protected AbstractJdbc42DatabaseMetaData(AbstractJdbc42Connection conn)
    {
        super(conn);
    }

   public long getMaxLogicalLobSize() throws SQLException
   {
       return 0;
   }

   public boolean supportsRefCursors() throws SQLException
   {
       return false;
   }

}
