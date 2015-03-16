/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.ParameterMetaData;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc4.AbstractJdbc4ParameterMetaData;

public class Jdbc42ParameterMetaData extends AbstractJdbc4ParameterMetaData implements ParameterMetaData
{

    public Jdbc42ParameterMetaData(BaseConnection connection, int oids[])
    {
        super(connection, oids);
    }

}
