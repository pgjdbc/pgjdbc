package org.postgresql.jdbc3;

import org.postgresql.core.*;

public class Jdbc3ResultSetMetaData extends org.postgresql.jdbc2.AbstractJdbc2ResultSetMetaData implements java.sql.ResultSetMetaData
{

	public Jdbc3ResultSetMetaData(BaseConnection connection, Field[] fields)
	{
		super(connection, fields);
	}

}

