package org.postgresql.jdbc3g;


import java.sql.*;

public class Jdbc3gBlob extends org.postgresql.jdbc3.AbstractJdbc3Blob implements java.sql.Blob
{

	public Jdbc3gBlob(org.postgresql.PGConnection conn, int oid) throws SQLException
	{
		super(conn, oid);
	}

}
