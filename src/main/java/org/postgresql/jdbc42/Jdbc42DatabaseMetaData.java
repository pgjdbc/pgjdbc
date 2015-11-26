/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.DatabaseMetaData;

public class Jdbc42DatabaseMetaData extends AbstractJdbc42DatabaseMetaData implements DatabaseMetaData
{

    public Jdbc42DatabaseMetaData(AbstractJdbc42Connection conn)
    {
        super(conn);
    }

}
