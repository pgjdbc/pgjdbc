/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/Jdbc4ParameterMetaData.java,v 1.1 2006/06/08 10:34:52 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.ParameterMetaData;
import org.postgresql.core.BaseConnection;

public class Jdbc4ParameterMetaData extends AbstractJdbc4ParameterMetaData implements ParameterMetaData {

    public Jdbc4ParameterMetaData(BaseConnection connection, int oids[])
    {
        super(connection, oids);
    }

}

