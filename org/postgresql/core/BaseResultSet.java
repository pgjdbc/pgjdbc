/*-------------------------------------------------------------------------
 *
 * BaseResultSet.java
 *	  The internal interface definition for a jdbc result set
 *
 * Copyright (c) 2003, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/core/BaseResultSet.java,v 1.2 2003/03/08 06:06:55 barry Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core;

import java.sql.*;

/**
 * Driver-internal resultset interface. Application code should not use
 * this interface.
 */
public interface BaseResultSet extends ResultSet
{
	/**
	 * Return a sanitized numeric string for a column. This handles
	 * "money" representations, stripping $ and () as appropriate.
	 *
	 * @param col the 1-based column to retrieve
	 * @return the sanitized string
	 * @throws SQLException if something goes wrong
	 */
	public String getFixedString(int col) throws SQLException;
}
