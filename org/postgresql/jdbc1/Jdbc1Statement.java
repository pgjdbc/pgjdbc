package org.postgresql.jdbc1;


import java.sql.*;

/* $Header$
 * This class implements the java.sql.Statement interface for JDBC1.
 * However most of the implementation is really done in 
 * org.postgresql.jdbc1.AbstractJdbc1Statement
 */
public class Jdbc1Statement extends org.postgresql.jdbc1.AbstractJdbc1Statement implements java.sql.Statement
{

	public Jdbc1Statement (Jdbc1Connection c)
	{
	    super(c);
	}

}
