/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v3/SimpleParameterList.java,v 1.5 2004/11/09 08:46:17 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import java.io.InputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;

import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.StreamWrapper;
import org.postgresql.util.GT;

/**
 * Parameter list for a single-statement V3 query.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class SimpleParameterList implements V3ParameterList {
    SimpleParameterList(int paramCount) {
        this.paramValues = new Object[paramCount];
        this.paramTypes = new int[paramCount];
        this.encoded = new byte[paramCount][];
    }

    private void bind(int index, Object value, int oid) throws SQLException {
        if (index < 1 || index > paramValues.length)
            throw new PSQLException(GT.tr("The column index is out of range: {0}, number of columns: {1}.", new Object[]{new Integer(index), new Integer(paramValues.length)}), PSQLState.INVALID_PARAMETER_VALUE );

        --index;

        encoded[index] = null;
        paramValues[index] = value;
        paramTypes[index] = oid;
    }

    public int getParameterCount() {
        return paramValues.length;
    }

    public void setIntParameter(int index, int value) throws SQLException {
        byte[] data = new byte[4];
        data[3] = (byte)value;
        data[2] = (byte)(value >> 8);
        data[1] = (byte)(value >> 16);
        data[0] = (byte)(value >> 24);
        bind(index, data, Oid.INT4);
    }

    public void setLiteralParameter(int index, String value, int oid) throws SQLException {
        bind(index, value, oid);
    }

    public void setStringParameter(int index, String value, int oid) throws SQLException {
        bind(index, value, oid);
    }

    public void setBytea(int index, byte[] data, int offset, int length) throws SQLException {
        bind(index, new StreamWrapper(data, offset, length), Oid.BYTEA);
    }

    public void setBytea(int index, InputStream stream, int length) throws SQLException {
        bind(index, new StreamWrapper(stream, length), Oid.BYTEA);
    }

    public void setNull(int index, int oid) throws SQLException {
        bind(index, NULL_OBJECT, oid);
    }

    public String toString(int index) {
        --index;

        if (paramValues[index] == null)
            return "?";
        else if (paramValues[index] == NULL_OBJECT)
            return "NULL";
        else
            return paramValues[index].toString();
    }

    public void checkAllParametersSet() throws SQLException {
        for (int i = 0; i < paramTypes.length; ++i)
        {
            if (paramValues[i] == null)
                throw new PSQLException(GT.tr("No value specified for parameter {0}.", new Integer(i + 1)), PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

    //
    // bytea helper
    //

    private static void streamBytea(PGStream pgStream, StreamWrapper wrapper) throws IOException {
        byte[] rawData = wrapper.getBytes();
        if (rawData != null)
        {
            pgStream.Send(rawData, wrapper.getOffset(), wrapper.getLength());
            return ;
        }

        pgStream.SendStream(wrapper.getStream(), wrapper.getLength());
    }

    //
    // Package-private V3 accessors
    //

    int getTypeOID(int index) {
        return paramTypes[index -1];
    }

    boolean isNull(int index) {
        return (paramValues[index -1] == NULL_OBJECT);
    }

    boolean isBinary(int index) {
        // Currently, only StreamWrapper uses the binary parameter form.
        return (paramValues[index -1] instanceof StreamWrapper);
    }

    int getV3Length(int index) {
        --index;

        // Null?
        if (paramValues[index] == NULL_OBJECT)
            throw new IllegalArgumentException("can't getV3Length() on a null parameter");

        // Directly encoded?
        if (paramValues[index] instanceof byte[])
            return ((byte[])paramValues[index]).length;

        // Binary-format bytea?
        if (paramValues[index] instanceof StreamWrapper)
            return ((StreamWrapper)paramValues[index]).getLength();

        // Already encoded?
        if (encoded[index] == null)
        {
            // Encode value and compute actual length using UTF-8.
            encoded[index] = Utils.encodeUTF8(paramValues[index].toString());
        }

        return encoded[index].length;
    }

    void writeV3Value(int index, PGStream pgStream) throws IOException {
        --index;

        // Null?
        if (paramValues[index] == NULL_OBJECT)
            throw new IllegalArgumentException("can't writeV3Value() on a null parameter");

        // Directly encoded?
        if (paramValues[index] instanceof byte[])
        {
            pgStream.Send((byte[])paramValues[index]);
            return ;
        }

        // Binary-format bytea?
        if (paramValues[index] instanceof StreamWrapper)
        {
            streamBytea(pgStream, (StreamWrapper)paramValues[index]);
            return ;
        }

        // Encoded string.
        if (encoded[index] == null)
            encoded[index] = Utils.encodeUTF8((String)paramValues[index]);
        pgStream.Send(encoded[index]);
    }

    public ParameterList copy() {
        SimpleParameterList newCopy = new SimpleParameterList(paramValues.length);
        System.arraycopy(paramValues, 0, newCopy.paramValues, 0, paramValues.length);
        System.arraycopy(paramTypes, 0, newCopy.paramTypes, 0, paramTypes.length);
        return newCopy;
    }

    public void clear() {
        Arrays.fill(paramValues, null);
        Arrays.fill(paramTypes, 0);
        Arrays.fill(encoded, null);
    }

    public SimpleParameterList[] getSubparams() {
        return null;
    }

    private final Object[] paramValues;
    private final int[] paramTypes;
    private final byte[][] encoded;

    /**
     * Marker object representing NULL; this distinguishes
     * "parameter never set" from "parameter set to null".
     */
    private final static Object NULL_OBJECT = new Object();
}

