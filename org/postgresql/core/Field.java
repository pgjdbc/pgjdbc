/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/Field.java,v 1.11 2005/12/03 21:44:08 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.sql.*;

/*
 */
public class Field
{
    //The V3 protocol defines two constants for the format of data
    public static final int TEXT_FORMAT = 0;
    public static final int BINARY_FORMAT = 1;

    private final int length;    // Internal Length of this field
    private final int oid;        // OID of the type
    private final int mod;        // type modifier of this field
    private final String columnLabel; // Column label
    private String columnName;        // Column name; null if undetermined
    private Integer nullable;         // Is this column nullable? null if undetermined.
    private Boolean autoIncrement;   // Is this column automatically numbered?

    private int format = TEXT_FORMAT;   // In the V3 protocol each field has a format
    // 0 = text, 1 = binary
    // In the V2 protocol all fields in a
    // binary cursor are binary and all
    // others are text

    private final int tableOid; // OID of table ( zero if no table )
    private final int positionInTable;

    // cache-fields

    /*
     * Construct a field based on the information fed to it.
     *
     * @param name the name (column name and label) of the field
     * @param oid the OID of the field
     * @param len the length of the field
     */
    public Field(String name, int oid, int length, int mod)
    {
        this(name, name, oid, length, mod, 0, 0);
    }

    /*
     * Constructor without mod parameter.
     *
     * @param name the name (column name and label) of the field
     * @param oid the OID of the field
     * @param len the length of the field
     */
    public Field(String name, int oid)
    {
        this(name, oid, 0, -1);
    }

    /*
     * Construct a field based on the information fed to it.
     *
     * @param columnLabel the column label of the field
     * @param columnName the column label the name of the field
     * @param oid the OID of the field
     * @param length the length of the field
     * @param tableOid the OID of the columns' table
     * @param positionInTable the position of column in the table (first column is 1, second column is 2, etc...)
     */
    public Field(String columnLabel, String columnName, int oid, int length, int mod, int tableOid, int positionInTable)
    {
        this.columnLabel = columnLabel;
        this.columnName = columnName;
        this.oid = oid;
        this.length = length;
        this.mod = mod;
        this.tableOid = tableOid;
        this.positionInTable = positionInTable;
    }

    /*
     * @return the oid of this Field's data type
     */
    public int getOID()
    {
        return oid;
    }

    /*
     * @return the mod of this Field's data type
     */
    public int getMod()
    {
        return mod;
    }

    /*
     * @return the column label of this Field's data type
     */
    public String getColumnLabel()
    {
        return columnLabel;
    }

    /*
     * @return the length of this Field's data type
     */
    public int getLength()
    {
        return length;
    }

    /*
     * @return the format of this Field's data (text=0, binary=1)
     */
    public int getFormat()
    {
        return format;
    }

    /*
     * @param format the format of this Field's data (text=0, binary=1)
     */
    public void setFormat(int format)
    {
        this.format = format;
    }

    /*
     * @return the columns' table oid, zero if no oid available
     */
    public int getTableOid()
    {
        return tableOid;
    }

    public int getPositionInTable()
    {
        return positionInTable;
    }

    public int getNullable(Connection con) throws SQLException
    {
        if (nullable != null)
            return nullable.intValue();

        if (tableOid == 0 || positionInTable == 0)
        {
            nullable = new Integer(ResultSetMetaData.columnNullableUnknown);
            return nullable.intValue();
        }

        ResultSet res = null;
        PreparedStatement ps = null;
        try
        {
            ps = con.prepareStatement("SELECT attnotnull FROM pg_catalog.pg_attribute WHERE attrelid = ? AND attnum = ?;");
            ps.setInt(1, tableOid);
            ps.setInt(2, positionInTable);
            res = ps.executeQuery();

            int nullResult = ResultSetMetaData.columnNullableUnknown;
            if (res.next())
                nullResult = res.getBoolean(1) ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable;

            nullable = new Integer(nullResult);
            return nullResult;
        }
        finally
        {
            if (res != null)
                res.close();
            if (ps != null)
                ps.close();
        }
    }

    public boolean getAutoIncrement(Connection con) throws SQLException
    {
        if (autoIncrement != null)
            return autoIncrement.booleanValue();

        if (tableOid == 0 || positionInTable == 0)
        {
            autoIncrement = Boolean.FALSE;
            return autoIncrement.booleanValue();
        }

        ResultSet res = null;
        PreparedStatement ps = null;
        try
        {
            final String sql = "SELECT 1 "
                                + " FROM pg_catalog.pg_attrdef "
                                + " WHERE adrelid = ? AND adnum = ? "
                                + "  AND pg_catalog.pg_get_expr(adbin, adrelid) "
                                + "      LIKE '%nextval(%'";

            ps = con.prepareStatement(sql);

            ps.setInt(1, tableOid);
            ps.setInt(2, positionInTable);
            res = ps.executeQuery();

            if (res.next())
            {
                autoIncrement = Boolean.TRUE;
            }
            else
            {
                autoIncrement = Boolean.FALSE;
            }
            return autoIncrement.booleanValue();

        }
        finally
        {
            if (res != null)
                res.close();
            if (ps != null)
                ps.close();
        }
    }

    public String getColumnName(Connection con) throws SQLException
    {
        if (columnName != null)
            return columnName;

        columnName = "";
        if (tableOid == 0 || positionInTable == 0)
        {
            return columnName;
        }

        ResultSet res = null;
        PreparedStatement ps = null;
        try
        {
            ps = con.prepareStatement("SELECT attname FROM pg_catalog.pg_attribute WHERE attrelid = ? AND attnum = ?");
            ps.setInt(1, tableOid);
            ps.setInt(2, positionInTable);
            res = ps.executeQuery();
            if (res.next())
                columnName = res.getString(1);

            return columnName;
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
