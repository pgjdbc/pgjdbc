/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2Array.java,v 1.18 2005/12/04 21:40:33 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.GT;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Map;
import java.util.Vector;
import java.util.GregorianCalendar;

/**
 * Array is used collect one column of query result data.
 *
 * <p>Read a field of type Array into either a natively-typed
 * Java array object or a ResultSet.  Accessor methods provide
 * the ability to capture array slices.
 *
 * <p>Other than the constructor all methods are direct implementations
 * of those specified for java.sql.Array.  Please refer to the javadoc
 * for java.sql.Array for detailed descriptions of the functionality
 * and parameters of the methods of this class.
 *
 * @see ResultSet#getArray
 */


public abstract class AbstractJdbc2Array
{
    private BaseConnection conn = null;
    private Field field = null;
    private BaseResultSet rs;
    private int idx = 0;
    private String rawString = null;

    /**
     * Create a new Array
     *
     * @param conn a database connection
     * @param idx 1-based index of the query field to load into this Array
     * @param field the Field descriptor for the field to load into this Array
     * @param rs the ResultSet from which to get the data for this Array
     */
    public AbstractJdbc2Array(BaseConnection conn, int idx, Field field, BaseResultSet rs )
    throws SQLException
    {
        this.conn = conn;
        this.field = field;
        this.rs = rs;
        this.idx = idx;
        this.rawString = rs.getFixedString(idx);
    }

    public Object getArray() throws SQLException
    {
        return getArrayImpl( 1, 0, null );
    }

    public Object getArray(long index, int count) throws SQLException
    {
        return getArrayImpl( index, count, null );
    }

    public Object getArrayImpl(Map map) throws SQLException
    {
        return getArrayImpl( 1, 0, map );
    }

    public Object getArrayImpl(long index, int count, Map map) throws SQLException
    {
        if ( map != null && !map.isEmpty()) // For now maps aren't supported.
            throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");

        if (index < 1)
            throw new PSQLException(GT.tr("The array index is out of range: {0}", new Long(index)), PSQLState.DATA_ERROR);
        Object retVal = null;

        ArrayList array = new ArrayList();

        /* Check if the String is also not an empty array
              * otherwise there will be an exception thrown below
              * in the ResultSet.toX with an empty string.
              * -- Doug Fields <dfields-pg-jdbc@pexicom.com> Feb 20, 2002 */

        if ( rawString != null && !rawString.equals("{}") )
        {
            char[] chars = rawString.toCharArray();
            StringBuffer sbuf = new StringBuffer();
            boolean foundOpen = false;
            boolean insideString = false;

            /**
             * Starting with 8.0 non-standard (beginning index
             * isn't 1) bounds the dimensions are returned in the
             * data formatted like so "[0:3]={0,1,2,3,4}".
             * Older versions simply do not return the bounds.
             *
             * Right now we ignore these bounds, but we could
             * consider allowing these index values to be used
             * even though the JDBC spec says 1 is the first
             * index.  I'm not sure what a client would like
             * to see, so we just retain the old behavior.
             */
            int startOffset = 0;
            if (chars[0] == '[')
            {
                while (chars[startOffset] != '=')
                {
                    startOffset++;
                }
                startOffset++; // skip =
            }

            for ( int i = startOffset; i < chars.length; i++ )
            {
                if ( chars[i] == '\\' )
                    //escape character that we need to skip
                    i++;
                else if (!insideString && chars[i] == '{' )
                {
                    if ( foundOpen )  // Only supports 1-D arrays for now
                        throw new PSQLException(GT.tr("Multi-dimensional arrays are currently not supported."),
                                                PSQLState.NOT_IMPLEMENTED);
                    foundOpen = true;
                    continue;
                }
                else if (chars[i] == '"')
                {
                    insideString = !insideString;
                    continue;
                }
                else if (!insideString && (chars[i] == ',' || chars[i] == '}') ||
                         i == chars.length - 1)
                {
                    if ( chars[i] != '"' && chars[i] != '}' && chars[i] != ',' )
                        sbuf.append(chars[i]);
                    array.add( sbuf.toString() );
                    sbuf = new StringBuffer();
                    continue;
                }
                sbuf.append( chars[i] );
            }
        }
        String[] arrayContents = (String[]) array.toArray( new String[array.size()] );
        if ( count == 0 )
            count = arrayContents.length;
        index--;
        if ( index + count > arrayContents.length )
            throw new PSQLException(GT.tr("The array index is out of range: {0}, number of elements: {1}.", new Object[]{new Long(index + count), new Long(arrayContents.length)}), PSQLState.DATA_ERROR);

        int i = 0;
        GregorianCalendar cal = null;
        switch ( getBaseType() )
        {
        case Types.BIT:
            retVal = new boolean[ count ];
            for ( ; count > 0; count-- )
                ((boolean[])retVal)[i++] = AbstractJdbc2ResultSet.toBoolean( arrayContents[(int)index++] );
            break;
        case Types.SMALLINT:
        case Types.INTEGER:
            retVal = new int[ count ];
            for ( ; count > 0; count-- )
                ((int[])retVal)[i++] = AbstractJdbc2ResultSet.toInt( arrayContents[(int)index++] );
            break;
        case Types.BIGINT:
            retVal = new long[ count ];
            for ( ; count > 0; count-- )
                ((long[])retVal)[i++] = AbstractJdbc2ResultSet.toLong( arrayContents[(int)index++] );
            break;
        case Types.NUMERIC:
            retVal = new BigDecimal[ count ];
            for ( ; count > 0; count-- )
                ((BigDecimal[])retVal)[i++] = AbstractJdbc2ResultSet.toBigDecimal( arrayContents[(int)index++], -1 );
            break;
        case Types.REAL:
            retVal = new float[ count ];
            for ( ; count > 0; count-- )
                ((float[])retVal)[i++] = AbstractJdbc2ResultSet.toFloat( arrayContents[(int)index++] );
            break;
        case Types.DOUBLE:
            retVal = new double[ count ];
            for ( ; count > 0; count-- )
                ((double[])retVal)[i++] = AbstractJdbc2ResultSet.toDouble( arrayContents[(int)index++] );
            break;
        case Types.CHAR:
        case Types.VARCHAR:
            retVal = new String[ count ];
            for ( ; count > 0; count-- )
                ((String[])retVal)[i++] = arrayContents[(int)index++];
            break;
        case Types.DATE:
            retVal = new java.sql.Date[ count ];
            for ( ; count > 0; count-- )
                ((java.sql.Date[])retVal)[i++] = conn.getTimestampUtils().toDate(null, arrayContents[(int)index++] );
            break;
        case Types.TIME:
            retVal = new java.sql.Time[ count ];
            for ( ; count > 0; count-- )
                ((java.sql.Time[])retVal)[i++] = conn.getTimestampUtils().toTime(null, arrayContents[(int)index++] );
            break;
        case Types.TIMESTAMP:
            retVal = new Timestamp[ count ];
            for ( ; count > 0; count-- )
                ((java.sql.Timestamp[])retVal)[i++] = conn.getTimestampUtils().toTimestamp(null, arrayContents[(int)index++] );
            break;

            // Other datatypes not currently supported.  If you are really using other types ask
            // yourself if an array of non-trivial data types is really good database design.
        default:
            if (conn.getLogger().logDebug())
                conn.getLogger().debug("getArrayImpl(long,int,Map) with "+getBaseTypeName());
            throw org.postgresql.Driver.notImplemented(this.getClass(), "getArrayImpl(long,int,Map)");
        }
        return retVal;
    }

    public int getBaseType() throws SQLException
    {
        return conn.getSQLType(getBaseTypeName());
    }

    public String getBaseTypeName() throws SQLException
    {
        String fType = conn.getPGType(field.getOID());
        if ( fType.charAt(0) == '_' )
            fType = fType.substring(1);
        return fType;
    }

    public java.sql.ResultSet getResultSet() throws SQLException
    {
        return getResultSetImpl( 1, 0, null );
    }

    public java.sql.ResultSet getResultSet(long index, int count) throws SQLException
    {
        return getResultSetImpl( index, count, null );
    }

    public java.sql.ResultSet getResultSetImpl(Map map) throws SQLException
    {
        return getResultSetImpl( 1, 0, map );
    }

    private void fillIntegerResultSet(long index, int[] intArray, Vector rows) throws SQLException
    {
        for ( int i = 0; i < intArray.length; i++ )
        {
            byte[][] tuple = new byte[2][0];
            tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
            tuple[1] = conn.encodeString( Integer.toString(intArray[i]) ); // Value
            rows.addElement(tuple);
        }
    }

    private void fillStringResultSet(long index, String[] strArray, Vector rows) throws SQLException
    {
        for ( int i = 0; i < strArray.length; i++ )
        {
            byte[][] tuple = new byte[2][0];
            tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
            tuple[1] = conn.encodeString( strArray[i] ); // Value
            rows.addElement(tuple);
        }
    }

    public java.sql.ResultSet getResultSetImpl(long index, int count, java.util.Map map) throws SQLException
    {
        Object array = getArrayImpl( index, count, map );
        Vector rows = new Vector();
        Field[] fields = new Field[2];
        fields[0] = new Field("INDEX", Oid.INT2);
        switch ( getBaseType() )
        {
        case Types.BIT:
            boolean[] booleanArray = (boolean[]) array;
            fields[1] = new Field("VALUE", Oid.BOOL);
            for ( int i = 0; i < booleanArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( (booleanArray[i] ? "YES" : "NO") ); // Value
                rows.addElement(tuple);
            }
            break;
        case Types.SMALLINT:
            fields[1] = new Field("VALUE", Oid.INT2);
            fillIntegerResultSet(index, (int[])array, rows);
            break;
        case Types.INTEGER:
            fields[1] = new Field("VALUE", Oid.INT4);
            fillIntegerResultSet(index, (int[])array, rows);
            break;
        case Types.BIGINT:
            long[] longArray = (long[]) array;
            fields[1] = new Field("VALUE", Oid.INT8);
            for ( int i = 0; i < longArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( Long.toString(longArray[i]) ); // Value
                rows.addElement(tuple);
            }
            break;
        case Types.NUMERIC:
            BigDecimal[] bdArray = (BigDecimal[]) array;
            fields[1] = new Field("VALUE", Oid.NUMERIC);
            for ( int i = 0; i < bdArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( bdArray[i].toString() ); // Value
                rows.addElement(tuple);
            }
            break;
        case Types.REAL:
            float[] floatArray = (float[]) array;
            fields[1] = new Field("VALUE", Oid.FLOAT4);
            for ( int i = 0; i < floatArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( Float.toString(floatArray[i]) ); // Value
                rows.addElement(tuple);
            }
            break;
        case Types.DOUBLE:
            double[] doubleArray = (double[]) array;
            fields[1] = new Field("VALUE", Oid.FLOAT8);
            for ( int i = 0; i < doubleArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( Double.toString(doubleArray[i]) ); // Value
                rows.addElement(tuple);
            }
            break;
        case Types.CHAR:
            fields[1] = new Field("VALUE", Oid.BPCHAR);
            fillStringResultSet(index, (String[])array, rows);
            break;
        case Types.VARCHAR:
            fields[1] = new Field("VALUE", Oid.VARCHAR);
            fillStringResultSet(index, (String[])array, rows);
            break;
        case Types.DATE:
            java.sql.Date[] dateArray = (java.sql.Date[]) array;
            fields[1] = new Field("VALUE", Oid.DATE);
            for ( int i = 0; i < dateArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( conn.getTimestampUtils().toString(null, dateArray[i]) ); // Value
                rows.addElement(tuple);
            }
            break;
        case Types.TIME:
            java.sql.Time[] timeArray = (java.sql.Time[]) array;
            fields[1] = new Field("VALUE", Oid.TIME);
            for ( int i = 0; i < timeArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( conn.getTimestampUtils().toString(null, timeArray[i]) ); // Value
                rows.addElement(tuple);
            }
            break;
        case Types.TIMESTAMP:
            java.sql.Timestamp[] timestampArray = (java.sql.Timestamp[]) array;
            fields[1] = new Field("VALUE", Oid.TIMESTAMPTZ);
            for ( int i = 0; i < timestampArray.length; i++ )
            {
                byte[][] tuple = new byte[2][0];
                tuple[0] = conn.encodeString( Integer.toString((int)index + i) ); // Index
                tuple[1] = conn.encodeString( conn.getTimestampUtils().toString(null, timestampArray[i]) ); // Value
                rows.addElement(tuple);
            }
            break;

            // Other datatypes not currently supported.  If you are really using other types ask
            // yourself if an array of non-trivial data types is really good database design.
        default:
            if (conn.getLogger().logDebug())
                conn.getLogger().debug("getResultSetImpl(long,int,Map) with "+getBaseTypeName());
            throw org.postgresql.Driver.notImplemented(this.getClass(), "getResultSetImpl(long,int,Map)");
        }
        BaseStatement stat = (BaseStatement) conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        return (ResultSet) stat.createDriverResultSet(fields, rows);
    }

    public String toString()
    {
        return rawString;
    }
}

