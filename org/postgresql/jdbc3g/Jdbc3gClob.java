package org.postgresql.jdbc3g;


public class Jdbc3gClob extends org.postgresql.jdbc3.AbstractJdbc3Clob implements java.sql.Clob
{

	public Jdbc3gClob(org.postgresql.PGConnection conn, int oid) throws java.sql.SQLException
	{
		super(conn, oid);
	}

}
