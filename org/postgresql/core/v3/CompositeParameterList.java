/*-------------------------------------------------------------------------
 *
 * CompositeParameterList.java
 *	  Parameter list for V3 query strings that contain multiple statements.
 *
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core.v3;

import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.io.InputStream;
import java.io.IOException;

/**
 * Parameter list for V3 query strings that contain multiple statements.
 * We delegate to one SimpleParameterList per statement, and translate
 * parameter indexes as needed.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class CompositeParameterList implements V3ParameterList {
	CompositeParameterList(SimpleParameterList[] subparams, int[] offsets) {
		this.subparams = subparams;
		this.offsets = offsets;
		this.total = offsets[offsets.length - 1] + subparams[offsets.length - 1].getParameterCount();
	}

	private final int findSubParam(int index) throws SQLException {
		if (index < 1 || index > total)
			throw new PSQLException("postgresql.prep.range", PSQLState.INVALID_PARAMETER_VALUE);

		for (int i = offsets.length - 1; i >= 0; --i)
			if (offsets[i] < index)
				return i;

		throw new IllegalArgumentException("I am confused; can't find a subparam for index " + index);
	}

	public int getParameterCount() {
		return total;
	}

	public void setIntParameter(int index, int value) throws SQLException {
		int sub = findSubParam(index);
		subparams[sub].setIntParameter(index - offsets[sub], value);
	}

	public void setLiteralParameter(int index, String value, int oid) throws SQLException {
		int sub = findSubParam(index);
		subparams[sub].setStringParameter(index - offsets[sub], value, oid);
	}

	public void setStringParameter(int index, String value, int oid) throws SQLException {
		int sub = findSubParam(index);
		subparams[sub].setStringParameter(index - offsets[sub], value, oid);
	}

	public void setBytea(int index, byte[] data, int offset, int length) throws SQLException {
		int sub = findSubParam(index);
		subparams[sub].setBytea(index - offsets[sub], data, offset, length);
	}
		
	public void setBytea(int index, InputStream stream, int length) throws SQLException {
		int sub = findSubParam(index);
		subparams[sub].setBytea(index - offsets[sub], stream, length);
	}

	public void setNull(int index, int oid) throws SQLException {
		int sub = findSubParam(index);
		subparams[sub].setNull(index - offsets[sub], oid);
	}

	public String toString(int index) {
		try {
			int sub = findSubParam(index);
			return subparams[sub].toString(index - offsets[sub]);
		} catch (SQLException e) {
			throw new IllegalStateException(e.getMessage());
		}
	}

	public ParameterList copy() {
		SimpleParameterList[] copySub = new SimpleParameterList[subparams.length];
		for (int sub = 0; sub < subparams.length; ++sub)
			copySub[sub] = (SimpleParameterList)subparams[sub].copy();

		return new CompositeParameterList(copySub, offsets);
	}

	public void clear() {
		for (int sub = 0; sub < subparams.length; ++sub) {
			subparams[sub].clear();
		}
	}		

	public SimpleParameterList[] getSubparams() {
		return subparams;
	}

	public void checkAllParametersSet() throws SQLException {
		for (int sub = 0; sub < subparams.length; ++sub)
			subparams[sub].checkAllParametersSet();
	}

	private final int total;
	private final SimpleParameterList[] subparams;
	private final int[] offsets;
}
