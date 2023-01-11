/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.core.BaseStatement;
import legacy.org.postgresql.core.Field;
import legacy.org.postgresql.core.Oid;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Map;
import java.util.Vector;

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

    /**
     * Create a new Array.
     *
     * @param connection a database connection
     * @param oid the oid of the array datatype
     * @param fieldString the array data in string form
     */
    public AbstractJdbc2Array(BaseConnection connection, int oid, String fieldString) throws SQLException {
        this.connection = connection;
        this.oid = oid;
        this.fieldString = fieldString;
        this.useObjects = connection.haveMinimumCompatibleVersion("8.3");
        this.haveMinServer82 = connection.haveMinimumServerVersion("8.2");
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
            throw Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
        }

        // array index is out of range
        if (index < 1)
        {
            throw new PSQLException(GT.tr("The array index is out of range: {0}", new Long(index)), PSQLState.DATA_ERROR);
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
            Vector dims = new Vector(); // array dimension arrays
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
                        PgArrayList p = ((PgArrayList) dims.lastElement());
                        p.add(a);
                        dims.add(a);
                    }
                    curArray = (PgArrayList) dims.lastElement();

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
                            curArray = (PgArrayList) dims.lastElement();
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

            throw Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
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
            throw Driver.notImplemented(this.getClass(), "getResultSetImpl(long,int,Map)");
        }

        // array index is out of range
        if (index < 1)
        {
            throw new PSQLException(GT.tr("The array index is out of range: {0}", new Long(index)), PSQLState.DATA_ERROR);
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

        Vector rows = new Vector();

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

}
