/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core;

import java.sql.ResultSetMetaData;

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
    private String columnName;        // Column name

    private int format = TEXT_FORMAT;   // In the V3 protocol each field has a format
    // 0 = text, 1 = binary
    // In the V2 protocol all fields in a
    // binary cursor are binary and all
    // others are text

    private final int tableOid; // OID of table ( zero if no table )
    private final int positionInTable;

    // Cache fields filled in by AbstractJdbc2ResultSetMetaData.fetchFieldMetaData.
    // Don't use unless that has been called.
    private String tableName = "";
    private String schemaName = "";
    private int nullable = ResultSetMetaData.columnNullableUnknown;
    private boolean autoIncrement = false;

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

    public void setNullable(int nullable)
    {
        this.nullable = nullable;
    }

    public int getNullable()
    {
        return nullable;
    }

    public void setAutoIncrement(boolean autoIncrement)
    {
        this.autoIncrement = autoIncrement;
    }

    public boolean getAutoIncrement()
    {
        return autoIncrement;
    }

    public void setColumnName(String columnName)
    {
        this.columnName = columnName;
    }

    public String getColumnName()
    {
        return columnName;
    }

    public void setTableName(String tableName)
    {
        this.tableName = tableName;
    }

    public String getTableName()
    {
        return tableName;
    }

    public void setSchemaName(String schemaName)
    {
        this.schemaName = schemaName;
    }

    public String getSchemaName()
    {
        return schemaName;
    }

}
