/*-------------------------------------------------------------------------
 *
 * FastpathParameterList.java
 *	  Parameter list for fastpath calls using the V2 protocol.
 *
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core.v2;

import org.postgresql.core.*;

import java.io.InputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.StreamWrapper;

/**
 * Implementation of fastpath parameter lists for the V2 protocol.
 * The V2 protocol expects different representations of parameters in
 * queries to that used in fastpath calls, so we do a separate implementation
 * which supports only what fastpath needs here.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class FastpathParameterList implements ParameterList {
	FastpathParameterList(int paramCount) {
		this.paramValues = new Object[paramCount];
	}

	public int getParameterCount() { 
		return paramValues.length;
	}

	public void setIntParameter(int index, int value) throws SQLException {
		if (index < 1 || index > paramValues.length)
			throw new PSQLException("postgresql.prep.range", PSQLState.INVALID_PARAMETER_VALUE);

		byte[] data = new byte[4];
		data[3] = (byte)value;
		data[2] = (byte)(value>>8);
		data[1] = (byte)(value>>16);
		data[0] = (byte)(value>>24);

		paramValues[index-1] = data;
	}

	public void setLiteralParameter(int index, String value, int oid) throws SQLException {
		// Not enough type info here for the V2 path (which requires binary reprs)
		throw new IllegalArgumentException("can't setLiteralParameter() on a fastpath parameter");
	}
		
	public void setStringParameter(int index, String value, int oid) throws SQLException {
		paramValues[index-1] = value;
	}
		
	public void setBytea(int index, byte[] data, int offset, int length) throws SQLException {		
		if (index < 1 || index > paramValues.length)
			throw new PSQLException("postgresql.prep.range", PSQLState.INVALID_PARAMETER_VALUE);

		paramValues[index-1] = new StreamWrapper(data, offset, length);
	}

	public void setBytea(int index, final InputStream stream, final int length) throws SQLException {
		if (index < 1 || index > paramValues.length)
			throw new PSQLException("postgresql.prep.range", PSQLState.INVALID_PARAMETER_VALUE);

		paramValues[index-1] = new StreamWrapper(stream, length);
	}

	public void setNull(int index, int oid) throws SQLException {
		throw new IllegalArgumentException("can't setNull() on a v2 fastpath parameter");
	}

	public String toString(int index) {
		if (index < 1 || index > paramValues.length)
			throw new IllegalArgumentException("parameter " + index + " out of range");

		return "<fastpath parameter>";
	}
	
	private void copyStream(PGStream pgStream, StreamWrapper wrapper) throws IOException {
		byte[] rawData = wrapper.getBytes();
		if (rawData != null) {
			pgStream.Send(rawData, wrapper.getOffset(), wrapper.getLength());
			return;
		}

		pgStream.SendStream(wrapper.getStream(), wrapper.getLength());
	}

	void writeV2FastpathValue(int index, PGStream pgStream) throws IOException {
		--index;

		if (paramValues[index] instanceof StreamWrapper) {
			StreamWrapper wrapper = (StreamWrapper)paramValues[index];
			pgStream.SendInteger4(wrapper.getLength());
			copyStream(pgStream, wrapper);
		} else if (paramValues[index] instanceof byte[]) {
			byte[] data = (byte[])paramValues[index];
			pgStream.SendInteger4(data.length);
			pgStream.Send(data);
		} else if (paramValues[index] instanceof String) {
			byte[] data = pgStream.getEncoding().encode((String)paramValues[index]);
			pgStream.SendInteger4(data.length);			
			pgStream.Send(data);
		} else {
			throw new IllegalArgumentException("don't know how to stream parameter " + index);
		}
	}

	void checkAllParametersSet() throws SQLException {
		for (int i = 0; i < paramValues.length; i++) {
			if (paramValues[i] == null)
				throw new PSQLException("postgresql.prep.param", PSQLState.INVALID_PARAMETER_VALUE, new Integer(i + 1));
		}
	}

	public ParameterList copy() {
		FastpathParameterList newCopy = new FastpathParameterList(paramValues.length);
		System.arraycopy(paramValues, 0, newCopy.paramValues, 0, paramValues.length);
		return newCopy;
	}

	public void clear() {
		Arrays.fill(paramValues, null);
	}

	private final Object[] paramValues;
}

