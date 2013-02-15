/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import org.postgresql.core.*;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.GT;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Array is used collect one column of query result data.
 *
 * <p>
 * Read a field of type Array into either a natively-typed Java array object or
 * a ResultSet. Accessor methods provide the ability to capture array slices.
 *
 * <p>
 * Other than the constructor all methods are direct implementations of those
 * specified for java.sql.Array. Please refer to the javadoc for java.sql.Array
 * for detailed descriptions of the functionality and parameters of the methods
 * of this class.
 *
 * @see ResultSet#getArray
 */
public abstract class AbstractJdbc2Array
{

    /**
     * Array list implementation specific for storing PG array elements.
     */
    private static class PgArrayList extends ArrayList
    {

        private static final long serialVersionUID = 2052783752654562677L;

        /**
         * How many dimensions.
         */
        int dimensionsCount = 1;

    }

    /**
     * A database connection.
     */
    private BaseConnection connection = null;

    /**
     * The OID of this field.
     */
    private int oid;

    /**
     * Field value as String.
     */
    private String fieldString = null;

    /**
     * Whether Object[] should be used instead primitive arrays. Object[] can
     * contain null elements. It should be set to <Code>true</Code> if
     * {@link BaseConnection#haveMinimumCompatibleVersion(String)} returns
     * <Code>true</Code> for argument "8.3".
     */
    private final boolean useObjects;

    /**
     * Are we connected to an 8.2 or higher server?  Only 8.2 or higher
     * supports null array elements.
     */
    private final boolean haveMinServer82;

    /**
     * Value of field as {@link PgArrayList}. Will be initialized only once
     * within {@link #buildArrayList()}.
     */
    private PgArrayList arrayList;

    private byte[] fieldBytes;

    private AbstractJdbc2Array(BaseConnection connection, int oid) throws SQLException {
        this.connection = connection;
        this.oid = oid;
        this.useObjects = connection.haveMinimumCompatibleVersion("8.3");
        this.haveMinServer82 = connection.haveMinimumServerVersion("8.2");
    }

    /**
     * Create a new Array.
     *
     * @param connection a database connection
     * @param oid the oid of the array datatype
     * @param fieldString the array data in string form
     */
    public AbstractJdbc2Array(BaseConnection connection, int oid, String fieldString) throws SQLException {
        this(connection, oid);
        this.fieldString = fieldString;
    }

    /**
     * Create a new Array.
     *
     * @param connection a database connection
     * @param oid the oid of the array datatype
     * @param fieldBytes the array data in byte form
     */
    public AbstractJdbc2Array(BaseConnection connection, int oid, byte[] fieldBytes) throws SQLException {
        this(connection, oid);
        this.fieldBytes = fieldBytes;
    }

    public Object getArray() throws SQLException
    {
        return getArrayImpl(1, 0, null);
    }

    public Object getArray(long index, int count) throws SQLException
    {
        return getArrayImpl(index, count, null);
    }

    public Object getArrayImpl(Map map) throws SQLException
    {
        return getArrayImpl(1, 0, map);
    }

    public Object getArrayImpl(long index, int count, Map map) throws SQLException
    {

        // for now maps aren't supported.
        if (map != null && !map.isEmpty())
        {
            throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
        }

        // array index is out of range
        if (index < 1)
        {
            throw new PSQLException(GT.tr("The array index is out of range: {0}", new Long(index)), PSQLState.DATA_ERROR);
        }

        if (fieldBytes != null) {
            return readBinaryArray((int) index, count);
        }

        buildArrayList();

        if (count == 0)
            count = arrayList.size();

        // array index out of range
        if ((--index) + count > arrayList.size())
        {
            throw new PSQLException(GT.tr("The array index is out of range: {0}, number of elements: {1}.", new Object[] { new Long(index + count), new Long(arrayList.size()) }), PSQLState.DATA_ERROR);
        }

        return buildArray(arrayList, (int) index, count);
    }

    private Object readBinaryArray(int index, int count) throws SQLException {
        int dimensions = ByteConverter.int4(fieldBytes, 0);
        //int flags = ByteConverter.int4(fieldBytes, 4); // bit 0: 0=no-nulls, 1=has-nulls
        int elementOid = ByteConverter.int4(fieldBytes, 8);
        int pos = 12;
        int[] dims = new int[dimensions];
        for (int d = 0; d < dimensions; ++d) {
            dims[d] = ByteConverter.int4(fieldBytes, pos); pos += 4;
            /*int lbound = ByteConverter.int4(fieldBytes, pos);*/ pos += 4;
        }
        if (dimensions == 0) {
            return java.lang.reflect.Array.newInstance(elementOidToClass(elementOid), 0);
        }
        if (count > 0) {
            dims[0] = Math.min(count, dims[0]);
        }
        Object arr = java.lang.reflect.Array.newInstance(elementOidToClass(elementOid), dims);
        try {
            storeValues((Object[]) arr, elementOid, dims, pos, 0, index);
        }
        catch (IOException ioe)
        {
            throw new PSQLException(GT.tr("Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."), PSQLState.DATA_ERROR, ioe);
        }
        return arr;
    }

    private int storeValues(final Object[] arr, int elementOid, final int[] dims, int pos, final int thisDimension, int index) throws SQLException, IOException {
        if (thisDimension == dims.length - 1) {
            for (int i = 1; i < index; ++i) {
                int len = ByteConverter.int4(fieldBytes, pos); pos += 4;
                if (len != -1) {
                    pos += len;
                }
            }
            for (int i = 0; i < dims[thisDimension]; ++i) {
                int len = ByteConverter.int4(fieldBytes, pos); pos += 4;
                if (len == -1) {
                    continue;
                }
                switch (elementOid) {
                case Oid.INT2:
                    arr[i] = new Short(ByteConverter.int2(fieldBytes, pos));
                    break;
                case Oid.INT4:
                    arr[i] = new Integer(ByteConverter.int4(fieldBytes, pos));
                    break;
                case Oid.INT8:
                    arr[i] = new Long(ByteConverter.int8(fieldBytes, pos));
                    break;
                case Oid.FLOAT4:
                    arr[i] = new Float(ByteConverter.float4(fieldBytes, pos));
                    break;
                case Oid.FLOAT8:
                    arr[i] = new Double(ByteConverter.float8(fieldBytes, pos));
                    break;
                case Oid.TEXT:
                case Oid.VARCHAR:
                    Encoding encoding = connection.getEncoding();
                    arr[i] = encoding.decode(fieldBytes, pos, len);
                    break;
                }
                pos += len;
            }
        } else {
            for (int i = 0; i < dims[thisDimension]; ++i) {
                pos = storeValues((Object[]) arr[i], elementOid, dims, pos, thisDimension + 1, 0);
            }
        }
        return pos;
    }

    
    private ResultSet readBinaryResultSet(int index, int count) throws SQLException {
        int dimensions = ByteConverter.int4(fieldBytes, 0);
        //int flags = ByteConverter.int4(fieldBytes, 4); // bit 0: 0=no-nulls, 1=has-nulls
        int elementOid = ByteConverter.int4(fieldBytes, 8);
        int pos = 12;
        int[] dims = new int[dimensions];
        for (int d = 0; d < dimensions; ++d) {
            dims[d] = ByteConverter.int4(fieldBytes, pos); pos += 4;
            /*int lbound = ByteConverter.int4(fieldBytes, pos); */ pos += 4;
        }
        if (count > 0 && dimensions > 0) {
            dims[0] = Math.min(count, dims[0]);
        }
        List rows = new ArrayList();
        Field[] fields = new Field[2];
        if (dimensions > 0) {
            storeValues(rows, fields, elementOid, dims, pos, 0, index);
        }
        BaseStatement stat = (BaseStatement) connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        return stat.createDriverResultSet(fields, rows);
    }

    private int storeValues(List rows, Field[] fields, int elementOid, final int[] dims, int pos, final int thisDimension, int index) throws SQLException {
        if (thisDimension == dims.length - 1) {
            fields[0] = new Field("INDEX", Oid.INT4);
            fields[0].setFormat(Field.BINARY_FORMAT);
            fields[1] = new Field("VALUE", elementOid);
            fields[1].setFormat(Field.BINARY_FORMAT);
            for (int i = 1; i < index; ++i) {
                int len = ByteConverter.int4(fieldBytes, pos); pos += 4;
                if (len != -1) {
                    pos += len;
                }
            }
            for (int i = 0; i < dims[thisDimension]; ++i) {
                byte[][] rowData = new byte[2][];
                rowData[0] = new byte[4];
                ByteConverter.int4(rowData[0], 0, i + index);
                rows.add(rowData);
                int len = ByteConverter.int4(fieldBytes, pos); pos += 4;
                if (len == -1) {
                    continue;
                }
                rowData[1] = new byte[len];
                System.arraycopy(fieldBytes, pos, rowData[1], 0, rowData[1].length);
                pos += len;
            }
        } else {
            fields[0] = new Field("INDEX", Oid.INT4);
            fields[0].setFormat(Field.BINARY_FORMAT);
            fields[1] = new Field("VALUE", oid);
            fields[1].setFormat(Field.BINARY_FORMAT);
            int nextDimension = thisDimension + 1;
            int dimensionsLeft = dims.length - nextDimension;
            for (int i = 1; i < index; ++i) {
                pos = calcRemainingDataLength(dims, pos, elementOid, nextDimension);
            }
            for (int i = 0; i < dims[thisDimension]; ++i) {
                byte[][] rowData = new byte[2][];
                rowData[0] = new byte[4];
                ByteConverter.int4(rowData[0], 0, i + index);
                rows.add(rowData);
                int dataEndPos = calcRemainingDataLength(dims, pos, elementOid, nextDimension);
                int dataLength = dataEndPos - pos;
                rowData[1] = new byte[12 + 8 * dimensionsLeft + dataLength];
                ByteConverter.int4(rowData[1], 0, dimensionsLeft);
                System.arraycopy(fieldBytes, 4, rowData[1], 4, 8);
                System.arraycopy(fieldBytes, 12 + nextDimension * 8, rowData[1], 12, dimensionsLeft * 8);
                System.arraycopy(fieldBytes, pos, rowData[1], 12 + dimensionsLeft * 8, dataLength);
                pos = dataEndPos;
            }
        }
        return pos;
    }

    private int calcRemainingDataLength(int[] dims, int pos, int elementOid, int thisDimension) {
        if (thisDimension == dims.length - 1) {
            for (int i = 0; i < dims[thisDimension]; ++i) {
                int len = ByteConverter.int4(fieldBytes, pos); pos += 4;
                if (len == -1) {
                    continue;
                }
                pos += len;
            }
        } else {
            pos = calcRemainingDataLength(dims, elementOid, pos, thisDimension + 1);
        }
        return pos;
    }

    private Class elementOidToClass(int oid)
            throws SQLException {
        switch (oid) {
        case Oid.INT2:
            return Short.class;
        case Oid.INT4:
            return Integer.class;
        case Oid.INT8:
            return Long.class;
        case Oid.FLOAT4:
            return Float.class;
        case Oid.FLOAT8:
            return Double.class;
        case Oid.TEXT:
        case Oid.VARCHAR:
            return String.class;
        default:
            throw org.postgresql.Driver.notImplemented(this.getClass(),
                    "readBinaryArray(data,oid)");
        }
    }

    /**
     * Build {@link ArrayList} from field's string input. As a result
     * of this method {@link #arrayList} is build. Method can be called
     * many times in order to make sure that array list is ready to use, however
     * {@link #arrayList} will be set only once during first call.
     */
    private synchronized void buildArrayList() throws SQLException
    {
        if (arrayList != null)
            return;

        arrayList = new PgArrayList();

        char delim = connection.getTypeInfo().getArrayDelimiter(oid);

        if (fieldString != null)
        {

            char[] chars = fieldString.toCharArray();
            StringBuffer buffer = null;
            boolean insideString = false;
            boolean wasInsideString = false; // needed for checking if NULL
            // value occured
            List dims = new ArrayList(); // array dimension arrays
            PgArrayList curArray = arrayList; // currently processed array

            // Starting with 8.0 non-standard (beginning index
            // isn't 1) bounds the dimensions are returned in the
            // data formatted like so "[0:3]={0,1,2,3,4}".
            // Older versions simply do not return the bounds.
            //
            // Right now we ignore these bounds, but we could
            // consider allowing these index values to be used
            // even though the JDBC spec says 1 is the first
            // index. I'm not sure what a client would like
            // to see, so we just retain the old behavior.
            int startOffset = 0;
            {
                if (chars[0] == '[')
                {
                    while (chars[startOffset] != '=')
                    {
                        startOffset++;
                    }
                    startOffset++; // skip =
                }
            }

            for (int i = startOffset; i < chars.length; i++)
            {

                // escape character that we need to skip
                if (chars[i] == '\\')
                    i++;

                // subarray start
                else if (!insideString && chars[i] == '{')
                {
                    if (dims.size() == 0)
                    {
                        dims.add(arrayList);
                    }
                    else
                    {
                        PgArrayList a = new PgArrayList();
                        PgArrayList p = ((PgArrayList) dims.get(dims.size() - 1));
                        p.add(a);
                        dims.add(a);
                    }
                    curArray = (PgArrayList) dims.get(dims.size() - 1);

                    // number of dimensions
                    {
                        for (int t = i + 1; t < chars.length; t++) {
                            if (Character.isWhitespace(chars[t])) continue;
                            else if (chars[t] == '{') curArray.dimensionsCount++;
                            else break;
                        }
                    }

                    buffer = new StringBuffer();
                    continue;
                }

                // quoted element
                else if (chars[i] == '"')
                {
                    insideString = !insideString;
                    wasInsideString = true;
                    continue;
                }

                // white space
                else if (!insideString && Character.isWhitespace(chars[i]))
                {
                    continue;
                }

                // array end or element end
                else if ((!insideString && (chars[i] == delim || chars[i] == '}')) || i == chars.length - 1)
                {

                    // when character that is a part of array element
                    if (chars[i] != '"' && chars[i] != '}' && chars[i] != delim && buffer != null)
                    {
                        buffer.append(chars[i]);
                    }

                    String b = buffer == null ? null : buffer.toString();

                    // add element to current array
                    if (b != null && (b.length() > 0 || wasInsideString))
                    {
                        curArray.add(!wasInsideString && haveMinServer82 && b.equals("NULL") ? null : b);
                    }

                    wasInsideString = false;
                    buffer = new StringBuffer();

                    // when end of an array
                    if (chars[i] == '}')
                    {
                        dims.remove(dims.size() - 1);

                        // when multi-dimension
                        if (dims.size() > 0)
                        {
                            curArray = (PgArrayList) dims.get(dims.size() - 1);
                        }

                        buffer = null;
                    }

                    continue;
                }

                if (buffer != null)
                    buffer.append(chars[i]);
            }
        }
    }

    /**
     * Convert {@link ArrayList} to array.
     *
     * @param input list to be converted into array
     */
    private Object buildArray (PgArrayList input, int index, int count) throws SQLException
    {

        if (count < 0)
            count = input.size();

        // array to be returned
        Object ret = null;

        // how many dimensions
        int dims = input.dimensionsCount;

        // dimensions length array (to be used with java.lang.reflect.Array.newInstance(Class<?>, int[]))
        int[] dimsLength = dims > 1 ? new int[dims] : null;
        if (dims > 1) {
            for (int i = 0; i < dims; i++) {
                dimsLength[i] = (i == 0 ? count : 0);
            }
        }

        // array elements counter
        int length = 0;

        // array elements type
        final int type = connection.getTypeInfo().getSQLType(connection.getTypeInfo().getPGArrayElement(oid));

        if (type == Types.BIT)
        {
            boolean[] pa = null; // primitive array
            Object[] oa = null; // objects array

            if (dims > 1 || useObjects)
            {
ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(useObjects ? Boolean.class : boolean.class, dimsLength) : new Boolean[count]);
            }
            else
            {
                ret = pa = new boolean[count];
            }

            // add elements
            for (; count > 0; count--)
            {
                Object o = input.get(index++);

                if (dims > 1 || useObjects)
                {
                    oa[length++] = o == null ? null : (dims > 1 ? buildArray((PgArrayList) o, 0, -1) : new Boolean(AbstractJdbc2ResultSet.toBoolean((String) o)));
                }
                else
                {
                    pa[length++] = o == null ? false : AbstractJdbc2ResultSet.toBoolean((String) o);
                }
            }
        }

        else if (type == Types.SMALLINT || type == Types.INTEGER)
        {
            int[] pa = null;
            Object[] oa = null;

            if (dims > 1 || useObjects)
            {
ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(useObjects ? Integer.class : int.class, dimsLength) : new Integer[count]);
            }
            else
            {
                ret = pa = new int[count];
            }

            for (; count > 0; count--)
            {
                Object o = input.get(index++);

                if (dims > 1 || useObjects)
                {
                    oa[length++] = o == null ? null : (dims > 1 ? buildArray((PgArrayList) o, 0, -1) : new Integer(AbstractJdbc2ResultSet.toInt((String) o)));
                }
                else
                {
                    pa[length++] = o == null ? 0 : AbstractJdbc2ResultSet.toInt((String) o);
                }
            }
        }

        else if (type == Types.BIGINT)
        {
            long[] pa = null;
            Object[] oa = null;

            if (dims > 1 || useObjects)
            {
ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(useObjects ? Long.class : long.class, dimsLength) : new Long[count]);
            }

            else
            {
                ret = pa = new long[count];
            }

            for (; count > 0; count--)
            {
                Object o = input.get(index++);

                if (dims > 1 || useObjects)
                {
                    oa[length++] = o == null ? null : (dims > 1 ? buildArray((PgArrayList) o, 0, -1) : new Long(AbstractJdbc2ResultSet.toLong((String) o)));
                }
                else
                {
                    pa[length++] = o == null ? 0l : AbstractJdbc2ResultSet.toLong((String) o);
                }
            }
        }

        else if (type == Types.NUMERIC)
        {
            Object[] oa = null;
            ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(BigDecimal.class, dimsLength) : new BigDecimal[count]);

            for (; count > 0; count--)
            {
                Object v = input.get(index++);
                oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1) : (v == null ? null : AbstractJdbc2ResultSet.toBigDecimal((String) v, -1));
            }
        }

        else if (type == Types.REAL)
        {
            float[] pa = null;
            Object[] oa = null;

            if (dims > 1 || useObjects)
            {
ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(useObjects ? Float.class : float.class, dimsLength) : new Float[count]);
            }
            else
            {
                ret = pa = new float[count];
            }

            for (; count > 0; count--)
            {
                Object o = input.get(index++);

                if (dims > 1 || useObjects)
                {
                    oa[length++] = o == null ? null : (dims > 1 ? buildArray((PgArrayList) o, 0, -1) : new Float(AbstractJdbc2ResultSet.toFloat((String) o)));
                }
                else
                {
                    pa[length++] = o == null ? 0f : AbstractJdbc2ResultSet.toFloat((String) o);
                }
            }
        }

        else if (type == Types.DOUBLE)
        {
            double[] pa = null;
            Object[] oa = null;

            if (dims > 1 || useObjects)
            {
ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(useObjects ? Double.class : double.class, dimsLength) : new Double[count]);
            }
            else
            {
                ret = pa = new double[count];
            }

            for (; count > 0; count--)
            {
                Object o = input.get(index++);

                if (dims > 1 || useObjects)
                {
                    oa[length++] = o == null ? null : (dims > 1 ? buildArray((PgArrayList) o, 0, -1) : new Double(AbstractJdbc2ResultSet.toDouble((String) o)));
                }
                else
                {
                    pa[length++] = o == null ? 0d : AbstractJdbc2ResultSet.toDouble((String) o);
                }
            }
        }

        else if (type == Types.CHAR || type == Types.VARCHAR)
        {
            Object[] oa = null;
            ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(String.class, dimsLength) : new String[count]);

            for (; count > 0; count--)
            {
                Object v = input.get(index++);
                oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1) : v;
            }
        }

        else if (type == Types.DATE)
        {
            Object[] oa = null;
            ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Date.class, dimsLength) : new java.sql.Date[count]);

            for (; count > 0; count--)
            {
                Object v = input.get(index++);
                oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1) : (v == null ? null : connection.getTimestampUtils().toDate(null, (String) v));
            }
        }

        else if (type == Types.TIME)
        {
            Object[] oa = null;
            ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Time.class, dimsLength) : new java.sql.Time[count]);

            for (; count > 0; count--)
            {
                Object v = input.get(index++);
                oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1) : (v == null ? null : connection.getTimestampUtils().toTime(null, (String) v));
            }
        }

        else if (type == Types.TIMESTAMP)
        {
            Object[] oa = null;
            ret = oa = (dims > 1 ? (Object[]) java.lang.reflect.Array.newInstance(java.sql.Timestamp.class, dimsLength) : new java.sql.Timestamp[count]);

            for (; count > 0; count--)
            {
                Object v = input.get(index++);
                oa[length++] = dims > 1 && v != null ? buildArray((PgArrayList) v, 0, -1) : (v == null ? null : connection.getTimestampUtils().toTimestamp(null, (String) v));
            }
        }

        // other datatypes not currently supported
        else
        {
            if (connection.getLogger().logDebug())
                connection.getLogger().debug("getArrayImpl(long,int,Map) with " + getBaseTypeName());

            throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
        }

        return ret;
    }

    public int getBaseType() throws SQLException
    {
        return connection.getTypeInfo().getSQLType(getBaseTypeName());
    }

    public String getBaseTypeName() throws SQLException
    {
        buildArrayList();
        int elementOID = connection.getTypeInfo().getPGArrayElement(oid);
        return connection.getTypeInfo().getPGType(elementOID);
    }

    public java.sql.ResultSet getResultSet() throws SQLException
    {
        return getResultSetImpl(1, 0, null);
    }

    public java.sql.ResultSet getResultSet(long index, int count) throws SQLException
    {
        return getResultSetImpl(index, count, null);
    }

    public java.sql.ResultSet getResultSetImpl(Map map) throws SQLException
    {
        return getResultSetImpl(1, 0, map);
    }

    public ResultSet getResultSetImpl(long index, int count, Map map) throws SQLException
    {

        // for now maps aren't supported.
        if (map != null && !map.isEmpty())
        {
            throw org.postgresql.Driver.notImplemented(this.getClass(), "getResultSetImpl(long,int,Map)");
        }

        // array index is out of range
        if (index < 1)
        {
            throw new PSQLException(GT.tr("The array index is out of range: {0}", new Long(index)), PSQLState.DATA_ERROR);
        }

        if (fieldBytes != null) {
            return readBinaryResultSet((int) index, count);
        }

        buildArrayList();

        if (count == 0)
        {
            count = arrayList.size();
        }

        // array index out of range
        if ((--index) + count > arrayList.size())
        {
            throw new PSQLException(GT.tr("The array index is out of range: {0}, number of elements: {1}.", new Object[] { new Long(index + count), new Long(arrayList.size()) }), PSQLState.DATA_ERROR);
        }

        List rows = new ArrayList();

        Field[] fields = new Field[2];

        // one dimensional array
        if (arrayList.dimensionsCount <= 1)
        {
            // array element type
            final int baseOid = connection.getTypeInfo().getPGArrayElement(oid);
            fields[0] = new Field("INDEX", Oid.INT4);
            fields[1] = new Field("VALUE", baseOid);

            for (int i = 0; i < count; i++)
            {
                int offset = (int)index + i;
                byte[][] t = new byte[2][0];
                String v = (String) arrayList.get(offset);
                t[0] = connection.encodeString(Integer.toString(offset + 1));
                t[1] = v == null ? null : connection.encodeString(v);
                rows.add(t);
            }
        }

        // when multi-dimensional
        else
        {
            fields[0] = new Field("INDEX", Oid.INT4);
            fields[1] = new Field("VALUE", oid);
            for (int i = 0; i < count; i++)
            {
                int offset = (int)index + i;
                byte[][] t = new byte[2][0];
                Object v = arrayList.get(offset);

                t[0] = connection.encodeString(Integer.toString(offset + 1));
                t[1] = v == null ? null : connection.encodeString(toString((PgArrayList) v));
                rows.add(t);
            }
        }

        BaseStatement stat = (BaseStatement) connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        return (ResultSet) stat.createDriverResultSet(fields, rows);
    }

    public String toString()
    {
        return fieldString;
    }

    /**
     * Convert array list to PG String representation (e.g. {0,1,2}).
     */
    private String toString(PgArrayList list) throws SQLException
    {
        StringBuffer b = new StringBuffer().append('{');

        char delim = connection.getTypeInfo().getArrayDelimiter(oid);

        for (int i = 0; i < list.size(); i++)
        {
            Object v = list.get(i);

            if (i > 0)
                b.append(delim);

            if (v == null)
                b.append("NULL");

            else if (v instanceof PgArrayList)
                b.append(toString((PgArrayList) v));

            else
                escapeArrayElement(b, (String)v);
        }

        b.append('}');

        return b.toString();
    }

    public static void escapeArrayElement(StringBuffer b, String s)
    {
        b.append('"');
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '"' || c == '\\') {
                b.append('\\');
            }

            b.append(c);
        }
        b.append('"');
    }

    public boolean isBinary() {
        return fieldBytes != null;
    }

    public byte[] toBytes() {
        return fieldBytes;
    }
}
