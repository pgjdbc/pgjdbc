/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc3/Jdbc3ParameterMetaData.java,v 1.2 2008/01/08 06:56:29 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;

import java.sql.ParameterMetaData;
import org.postgresql.core.BaseConnection;

public class Jdbc3ParameterMetaData extends AbstractJdbc3ParameterMetaData implements ParameterMetaData {

    public Jdbc3ParameterMetaData(BaseConnection connection, int oids[])
    {
        super(connection, oids);
    }

}

