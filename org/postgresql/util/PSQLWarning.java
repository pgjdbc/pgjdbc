/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.util;

import java.sql.SQLWarning;

public class PSQLWarning extends SQLWarning
{

	private ServerErrorMessage serverError;

	public PSQLWarning(ServerErrorMessage err)
	{
		this.serverError = err;
	}

	public String toString()
	{
		return serverError.toString();
	}

	public String getSQLState()
	{
		return serverError.getSQLState();
	}
}
