/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/AbstractJdbc2ResultSetMetaData.java,v 1.21 2008/01/08 06:56:28 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import org.postgresql.PGResultSetMetaData;
import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import java.sql.*;
import java.util.Hashtable;
import org.postgresql.util.GT;

public abstract class AbstractJdbc2ResultSetMetaData implements PGResultSetMetaData
{
    protected final BaseConnection connection;
    protected final Field[] fields;

    private Hashtable tableNameCache;
    private Hashtable schemaNameCache;

    /*
     * Initialise for a result with a tuple set and
     * a field descriptor set
     *
     * @param fields the array of field descriptors
     */
    public AbstractJdbc2ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        this.connection = connection;
        this.fields = fields;
    }

    /*
     * Whats the number of columns in the ResultSet?
     *
     * @return the number
     * @exception SQLException if a database access error occurs
     */
    public int getColumnCount() throws SQLException
    {
        return fields.length;
    }

    /*
     * Is the column automatically numbered (and thus read-only)
     * I believe that PostgreSQL does not support this feature.
     *
     * @param column the first column is 1, the second is 2...
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isAutoIncrement(int column) throws SQLException
    {
        Field field = getField(column);
        return field.getAutoIncrement(connection);
    }

    /*
     * Does a column's case matter? ASSUMPTION: Any field that is
     * not obviously case insensitive is assumed to be case sensitive
     *
     * @param column the first column is 1, the second is 2...
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isCaseSensitive(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().isCaseSensitive(field.getOID());
    }

    /*
     * Can the column be used in a WHERE clause?  Basically for
     * this, I split the functions into two types: recognised
     * types (which are always useable), and OTHER types (which
     * may or may not be useable). The OTHER types, for now, I
     * will assume they are useable.  We should really query the
     * catalog to see if they are useable.
     *
     * @param column the first column is 1, the second is 2...
     * @return true if they can be used in a WHERE clause
     * @exception SQLException if a database access error occurs
     */
    public boolean isSearchable(int column) throws SQLException
    {
        return true;
    }

    /*
     * Is the column a cash value? 6.1 introduced the cash/money
     * type, which haven't been incorporated as of 970414, so I
     * just check the type name for both 'cash' and 'money'
     *
     * @param column the first column is 1, the second is 2...
     * @return true if its a cash column
     * @exception SQLException if a database access error occurs
     */
    public boolean isCurrency(int column) throws SQLException
    {
        String type_name = getPGType(column);

        return type_name.equals("cash") || type_name.equals("money");
    }

    /*
     * Indicates the nullability of values in the designated column.
     *
     * @param column the first column is 1, the second is 2...
     * @return one of the columnNullable values
     * @exception SQLException if a database access error occurs
     */
    public int isNullable(int column) throws SQLException
    {
        Field field = getField(column);
        return field.getNullable(connection);
    }

    /*
     * Is the column a signed number? In PostgreSQL, all numbers
     * are signed, so this is trivial. However, strings are not
     * signed (duh!)
     *
     * @param column the first column is 1, the second is 2...
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isSigned(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().isSigned(field.getOID());
    }

    /*
     * What is the column's normal maximum width in characters?
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the maximum width
     * @exception SQLException if a database access error occurs
     */
    public int getColumnDisplaySize(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().getDisplaySize(field.getOID(), field.getMod());
    }

    /*
     * @param column the first column is 1, the second is 2, etc.
     * @return the column label
     * @exception SQLException if a database access error occurs
     */
    public String getColumnLabel(int column) throws SQLException
    {
        Field field = getField(column);
        return field.getColumnLabel();
    }

    /*
     * What's a column's name?
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the column name
     * @exception SQLException if a database access error occurs
     */
    public String getColumnName(int column) throws SQLException
    {
        return getColumnLabel(column);
    }

    public String getBaseColumnName(int column) throws SQLException {
        Field field = getField(column);
        return field.getColumnName(connection);
    }

    /*
     * @param column the first column is 1, the second is 2...
     * @return the Schema Name
     * @exception SQLException if a database access error occurs
     */
    public String getSchemaName(int column) throws SQLException
    {
        return "";
    }

    public String getBaseSchemaName(int column) throws SQLException
    {
        Field field = getField(column);
        if (field.getTableOid() == 0)
        {
            return "";
        }
        Integer tableOid = new Integer(field.getTableOid());
        if (schemaNameCache == null)
        {
            schemaNameCache = new Hashtable();
        }
        String schemaName = (String) schemaNameCache.get(tableOid);
        if (schemaName != null)
        {
            return schemaName;
        }
        else
        {
            ResultSet res = null;
            PreparedStatement ps = null;
            try
            {
                String sql = "SELECT n.nspname FROM pg_catalog.pg_class c, pg_catalog.pg_namespace n WHERE n.oid = c.relnamespace AND c.oid = ?;";
                ps = ((Connection)connection).prepareStatement(sql);
                ps.setInt(1, tableOid.intValue());
                res = ps.executeQuery();
                schemaName = "";
                if (res.next())
                {
                    schemaName = res.getString(1);
                }
                schemaNameCache.put(tableOid, schemaName);
                return schemaName;
            }
            finally
            {
                if (res != null)
                    res.close();
                if (ps != null)
                    ps.close();
            }
        }
    }

    /*
     * What is a column's number of decimal digits.
     *
     * @param column the first column is 1, the second is 2...
     * @return the precision
     * @exception SQLException if a database access error occurs
     */
    public int getPrecision(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().getPrecision(field.getOID(), field.getMod());
    }

    /*
     * What is a column's number of digits to the right of the
     * decimal point?
     *
     * @param column the first column is 1, the second is 2...
     * @return the scale
     * @exception SQLException if a database access error occurs
     */
    public int getScale(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().getScale(field.getOID(), field.getMod());
    }

    /*
     * @param column the first column is 1, the second is 2...
     * @return column name, or "" if not applicable
     * @exception SQLException if a database access error occurs
     */
    public String getTableName(int column) throws SQLException
    {
        return "";
    }

    public String getBaseTableName(int column) throws SQLException
    {
        Field field = getField(column);
        if (field.getTableOid() == 0)
        {
            return "";
        }
        Integer tableOid = new Integer(field.getTableOid());
        if (tableNameCache == null)
        {
            tableNameCache = new Hashtable();
        }
        String tableName = (String) tableNameCache.get(tableOid);
        if (tableName != null)
        {
            return tableName;
        }
        else
        {
            ResultSet res = null;
            PreparedStatement ps = null;
            try
            {
                ps = ((Connection)connection).prepareStatement("SELECT relname FROM pg_catalog.pg_class WHERE oid = ?");
                ps.setInt(1, tableOid.intValue());
                res = ps.executeQuery();
                tableName = "";
                if (res.next())
                {
                    tableName = res.getString(1);
                }
                tableNameCache.put(tableOid, tableName);
                return tableName;
            }
            finally
            {
                if (res != null)
                    res.close();
                if (ps != null)
                    ps.close();
            }
        }
    }

    /*
     * What's a column's table's catalog name?  As with getSchemaName(),
     * we can say that if getTableName() returns n/a, then we can too -
     * otherwise, we need to work on it.
     *
     * @param column the first column is 1, the second is 2...
     * @return catalog name, or "" if not applicable
     * @exception SQLException if a database access error occurs
     */
    public String getCatalogName(int column) throws SQLException
    {
        return "";
    }

    /*
     * What is a column's SQL Type? (java.sql.Type int)
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the java.sql.Type value
     * @exception SQLException if a database access error occurs
     * @see org.postgresql.Field#getSQLType
     * @see java.sql.Types
     */
    public int getColumnType(int column) throws SQLException
    {
        return getSQLType(column);
    }

    /*
     * Whats is the column's data source specific type name?
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the type name
     * @exception SQLException if a database access error occurs
     */
    public String getColumnTypeName(int column) throws SQLException
    {
        return getPGType(column);
    }

    /*
     * Is the column definitely not writable?  In reality, we would
     * have to check the GRANT/REVOKE stuff for this to be effective,
     * and I haven't really looked into that yet, so this will get
     * re-visited.
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isReadOnly(int column) throws SQLException
    {
        return false;
    }

    /*
     * Is it possible for a write on the column to succeed?  Again, we
     * would in reality have to check the GRANT/REVOKE stuff, which
     * I haven't worked with as yet.  However, if it isn't ReadOnly, then
     * it is obviously writable.
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isWritable(int column) throws SQLException
    {
        return !isReadOnly(column);
    }

    /*
     * Will a write on this column definately succeed? Hmmm...this
     * is a bad one, since the two preceding functions have not been
     * really defined. I cannot tell is the short answer. I thus
     * return isWritable() just to give us an idea.
     *
     * @param column the first column is 1, the second is 2, etc..
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isDefinitelyWritable(int column) throws SQLException
    {
        return false;
    }

    // ********************************************************
    // END OF PUBLIC INTERFACE
    // ********************************************************

    /*
     * For several routines in this package, we need to convert
     * a columnIndex into a Field[] descriptor.  Rather than do
     * the same code several times, here it is.
     *
     * @param columnIndex the first column is 1, the second is 2...
     * @return the Field description
     * @exception SQLException if a database access error occurs
     */
    protected Field getField(int columnIndex) throws SQLException
    {
        if (columnIndex < 1 || columnIndex > fields.length)
            throw new PSQLException(GT.tr("The column index is out of range: {0}, number of columns: {1}.", new Object[]{new Integer(columnIndex), new Integer(fields.length)}), PSQLState.INVALID_PARAMETER_VALUE );
        return fields[columnIndex - 1];
    }

    protected String getPGType(int columnIndex) throws SQLException
    {
        return connection.getTypeInfo().getPGType(getField(columnIndex).getOID());
    }

    protected int getSQLType(int columnIndex) throws SQLException
    {
        return connection.getTypeInfo().getSQLType(getField(columnIndex).getOID());
    }


    // ** JDBC 2 Extensions **

    // This can hook into our PG_Object mechanism
    /**
     * Returns the fully-qualified name of the Java class whose instances
     * are manufactured if the method <code>ResultSet.getObject</code>
     * is called to retrieve a value from the column.
     *
     * <code>ResultSet.getObject</code> may return a subclass of the class
     * returned by this method.
     *
     * @param column the first column is 1, the second is 2, ...
     * @return the fully-qualified name of the class in the Java programming
     *     language that would be used by the method
     *     <code>ResultSet.getObject</code> to retrieve the value in the specified
     *     column. This is the class name used for custom mapping.
     * @exception SQLException if a database access error occurs
     */
    public String getColumnClassName(int column) throws SQLException
    {
        Field field = getField(column);
        String result = connection.getTypeInfo().getJavaClass(field.getOID());

        if (result != null)
            return result;

        int sqlType = getSQLType(column);
        switch(sqlType) {
            case Types.ARRAY:
                return ("java.sql.Array");
            default:
                String type = getPGType(column);
                if ("unknown".equals(type)) {
                    return ("java.lang.String");
                }
                return ("java.lang.Object");
        }
    }
}

