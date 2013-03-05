/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3g;

import java.sql.ParameterMetaData;
import org.postgresql.core.BaseConnection;

public class Jdbc3gParameterMetaData extends org.postgresql.jdbc3.AbstractJdbc3ParameterMetaData implements ParameterMetaData {

    public Jdbc3gParameterMetaData(BaseConnection connection, int oids[])
    {
        super(connection, oids);
    }

}

