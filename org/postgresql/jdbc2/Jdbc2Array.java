package org.postgresql.jdbc2;

import java.util.Map;
import org.postgresql.core.*;
import java.sql.SQLException;
import java.sql.ResultSet;

public class Jdbc2Array extends AbstractJdbc2Array implements java.sql.Array
{
	public Jdbc2Array(BaseConnection conn, int idx, Field field, BaseResultSet rs) throws SQLException
	{
		super(conn, idx, field, rs);
	}

	public Object getArray(Map map) throws SQLException
	{
		return getArrayImpl(map);
	}

	public Object getArray(long index, int count, Map map) throws SQLException
	{
		return getArrayImpl(index, count, map);
	}

	public ResultSet getResultSet(Map map) throws SQLException
	{
		return getResultSetImpl(map);
	}

	public ResultSet getResultSet(long index, int count, Map map) throws SQLException
	{
		return getResultSetImpl(index, count, map);
	}

}
