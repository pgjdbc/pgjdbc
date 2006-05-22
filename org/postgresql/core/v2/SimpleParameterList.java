/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v2/SimpleParameterList.java,v 1.7 2005/07/04 18:50:28 davec Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v2;

import org.postgresql.core.*;

import java.io.InputStream;
import java.io.Writer;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.StreamWrapper;
import org.postgresql.util.GT;

/**
 * Parameter list for query parameters in the V2 protocol.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class SimpleParameterList implements ParameterList {
    SimpleParameterList(int paramCount) {
        this.paramValues = new Object[paramCount];
    }
    public void registerOutParameter(int index, int sqlType ){};
    public void registerOutParameter(int index, int sqlType, int precision ){};
    
    public int getInParameterCount() {
        return paramValues.length;
    }
    public int getParameterCount()
    {
        return paramValues.length;
    }
    public int getOutParameterCount()
    {
        return 1;
    }
    public int[] getTypeOIDs() {
        return null;
    }

    public void setIntParameter(int index, int value) throws SQLException {
        setLiteralParameter(index, "" + value, Oid.INT4);
    }

    public void setLiteralParameter(int index, String value, int oid) throws SQLException {
        if (index < 1 || index > paramValues.length)
            throw new PSQLException(GT.tr("The column index is out of range: {0}, number of columns: {1}.", new Object[]{new Integer(index), new Integer(paramValues.length)}), PSQLState.INVALID_PARAMETER_VALUE );

        paramValues[index - 1] = value;
    }

    public void setStringParameter(int index, String value, int oid) throws SQLException {
        StringBuffer sbuf = new StringBuffer(2 + value.length() * 11 / 10); // Add 10% for escaping.
        sbuf.append('\'');
        for (int i = 0; i < value.length(); ++i)
        {
            char ch = value.charAt(i);
            if (ch == '\0')
                throw new PSQLException(GT.tr("Zero bytes may not occur in string parameters."), PSQLState.INVALID_PARAMETER_VALUE);
            if (ch == '\\' || ch == '\'')
                sbuf.append('\\');
            sbuf.append(ch);
        }
        sbuf.append('\'');

        setLiteralParameter(index, sbuf.toString(), oid);
    }

    public void setBytea(int index, byte[] data, int offset, int length) throws SQLException {
        if (index < 1 || index > paramValues.length)
            throw new PSQLException(GT.tr("The column index is out of range: {0}, number of columns: {1}.", new Object[]{new Integer(index), new Integer(paramValues.length)}), PSQLState.INVALID_PARAMETER_VALUE );

        paramValues[index - 1] = new StreamWrapper(data, offset, length);
    }

    public void setBytea(int index, final InputStream stream, final int length) throws SQLException {
        if (index < 1 || index > paramValues.length)
            throw new PSQLException(GT.tr("The column index is out of range: {0}, number of columns: {1}.", new Object[]{new Integer(index), new Integer(paramValues.length)}), PSQLState.INVALID_PARAMETER_VALUE );

        paramValues[index - 1] = new StreamWrapper(stream, length);
    }

    public void setNull(int index, int oid) throws SQLException {
        if (index < 1 || index > paramValues.length)
            throw new PSQLException(GT.tr("The column index is out of range: {0}, number of columns: {1}.", new Object[]{new Integer(index), new Integer(paramValues.length)}), PSQLState.INVALID_PARAMETER_VALUE );

        paramValues[index - 1] = NULL_OBJECT;
    }

    public String toString(int index) {
        if (index < 1 || index > paramValues.length)
            throw new IllegalArgumentException("Parameter index " + index + " out of range");

        if (paramValues[index - 1] == null)
            return "?";
        else if (paramValues[index -1] == NULL_OBJECT)
            return "NULL";
        else
            return paramValues[index -1].toString();
    }

    /**
     * Send a streamable bytea encoded as a text representation with an arbitary encoding.
     */
    private static void streamBytea(StreamWrapper param, Writer encodingWriter) throws IOException {
        // NB: we escape everything in this path, as I don't like assuming
        // that byte values 32..127 will make it through the encoding
        // unscathed..

        InputStream stream = param.getStream();
        char[] buffer = new char[] { '\\', '\\', 0, 0, 0 };

        encodingWriter.write('\'');
        for (int remaining = param.getLength(); remaining > 0; --remaining)
        {
            int nextByte = stream.read();

            buffer[2] = (char)( '0' + ((nextByte >> 6) & 3));
            buffer[3] = (char)( '0' + ((nextByte >> 3) & 7));
            buffer[4] = (char)( '0' + (nextByte & 7));

            encodingWriter.write(buffer, 0, 5);
        }

        encodingWriter.write('\'');
    }


    void writeV2Value(int index, Writer encodingWriter) throws IOException {
        if (paramValues[index - 1] instanceof StreamWrapper)
        {
            streamBytea((StreamWrapper)paramValues[index - 1], encodingWriter);
        }
        else
        {
            encodingWriter.write((String)paramValues[index - 1]);
        }
    }

    void checkAllParametersSet() throws SQLException {
        for (int i = 0; i < paramValues.length; i++)
        {
            if (paramValues[i] == null)
                throw new PSQLException(GT.tr("No value specified for parameter {0}.", new Integer(i + 1)), PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

    public ParameterList copy() {
        SimpleParameterList newCopy = new SimpleParameterList(paramValues.length);
        System.arraycopy(paramValues, 0, newCopy.paramValues, 0, paramValues.length);
        return newCopy;
    }

    public void clear() {
        Arrays.fill(paramValues, null);
    }

    private final Object[] paramValues;

    /* Object representing NULL; conveniently, String streams exactly as we want it to. *
     * nb: we explicitly say "new String" to avoid interning giving us an object that
     * might be the same (by identity) as a String elsewhere.
     */
    private final static String NULL_OBJECT = new String("NULL");
}

