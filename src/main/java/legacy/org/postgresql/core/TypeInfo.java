/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core;

import java.sql.SQLException;
import java.util.Iterator;

public interface TypeInfo
{
    public void addCoreType(String pgTypeName, Integer oid, Integer sqlType, String javaClass, Integer arrayOid);

    public void addDataType(String type, Class klass) throws SQLException;

    /**
     * Look up the SQL typecode for a given type oid.
     *
     * @param oid the type's OID
     * @return the SQL type code (a constant from {@link java.sql.Types})
     *         for the type
     */
    public int getSQLType(int oid) throws SQLException;

    /**
     * Look up the SQL typecode for a given postgresql type name.
     *
     * @param pgTypeName the server type name to look up
     * @return the SQL type code (a constant from {@link java.sql.Types})
     *         for the type
     */
    public int getSQLType(String pgTypeName) throws SQLException;

    /**
     * Look up the oid for a given postgresql type name.  This is
     * the inverse of {@link #getPGType(int)}.
     *
     * @param pgTypeName the server type name to look up
     * @return the type's OID, or 0 if unknown
     */
    public int getPGType(String pgTypeName) throws SQLException;

    /**
     * Look up the postgresql type name for a given oid.  This is the
     * inverse of {@link #getPGType(String)}.
     *
     * @param oid the type's OID
     * @return the server type name for that OID or null if unknown
     */
    public String getPGType(int oid) throws SQLException;

    /**
     * Look up the oid of an array's base type given the array's type oid.
     *
     * @param oid the array type's OID
     * @return the base type's OID, or 0 if unknown
     */
    public int getPGArrayElement(int oid) throws SQLException;

    /**
     * Determine the oid of the given base postgresql type's array type
     *
     * @param elementTypeName the base type's
     * @return the array type's OID, or 0 if unknown
     */
    public int getPGArrayType(String elementTypeName) throws SQLException;

    /**
     * Determine the delimiter for the elements of the given array type oid.
     *
     * @param oid the array type's OID
     * @return the base type's array type delimiter
     */
    public char getArrayDelimiter(int oid) throws SQLException;

    public Iterator getPGTypeNamesWithSQLTypes();

    public Class getPGobject(String type);

    public String getJavaClass(int oid) throws SQLException;

    public String getTypeForAlias(String alias);

    public int getPrecision(int oid, int typmod);

    public int getScale(int oid, int typmod);

    public boolean isCaseSensitive(int oid);

    public boolean isSigned(int oid);

    public int getDisplaySize(int oid, int typmod);

    public int getMaximumPrecision(int oid);

    public boolean requiresQuoting(int oid) throws SQLException;

}
