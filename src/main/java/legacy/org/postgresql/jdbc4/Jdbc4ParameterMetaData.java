/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import java.sql.ParameterMetaData;

import legacy.org.postgresql.core.BaseConnection;

public class Jdbc4ParameterMetaData extends AbstractJdbc4ParameterMetaData implements ParameterMetaData {

    public Jdbc4ParameterMetaData(BaseConnection connection, int oids[])
    {
        super(connection, oids);
    }

}

