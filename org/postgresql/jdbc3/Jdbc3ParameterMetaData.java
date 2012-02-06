/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
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

