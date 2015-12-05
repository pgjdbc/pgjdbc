/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.sql.SQLException;
import java.util.Iterator;

public interface TypeInfo {
    void addCoreType(String pgTypeName, Integer oid, Integer sqlType, String javaClass, Integer arrayOid);

    void addDataType(String type, Class klass) throws SQLException;

    /**
     * Look up the SQL typecode for a given type oid.
     *
     * @param oid the type's OID
     * @return the SQL type code (a constant from {@link java.sql.Types})
     * for the type
     * @throws SQLException
     */
    int getSQLType(int oid) throws SQLException;

    /**
     * Look up the SQL typecode for a given postgresql type name.
     *
     * @param pgTypeName the server type name to look up
     * @return the SQL type code (a constant from {@link java.sql.Types})
     * for the type
     * @throws SQLException
     */
    int getSQLType(String pgTypeName) throws SQLException;

    /**
     * Look up the oid for a given postgresql type name.  This is
     * the inverse of {@link #getPGType(int)}.
     *
     * @param pgTypeName the server type name to look up
     * @return the type's OID, or 0 if unknown
     * @throws SQLException
     */
    int getPGType(String pgTypeName) throws SQLException;

    /**
     * Look up the postgresql type name for a given oid.  This is the
     * inverse of {@link #getPGType(String)}.
     *
     * @param oid the type's OID
     * @return the server type name for that OID or null if unknown
     * @throws SQLException
     */
    String getPGType(int oid) throws SQLException;

    /**
     * Look up the oid of an array's base type given the array's type oid.
     *
     * @param oid the array type's OID
     * @return the base type's OID, or 0 if unknown
     * @throws SQLException
     */
    int getPGArrayElement(int oid) throws SQLException;

    /**
     * Determine the oid of the given base postgresql type's array type
     *
     * @param elementTypeName the base type's
     * @return the array type's OID, or 0 if unknown
     * @throws SQLException
     */
    int getPGArrayType(String elementTypeName) throws SQLException;

    /**
     * Determine the delimiter for the elements of the given array type oid.
     *
     * @param oid the array type's OID
     * @return the base type's array type delimiter
     * @throws SQLException
     */
    char getArrayDelimiter(int oid) throws SQLException;

    Iterator getPGTypeNamesWithSQLTypes();

    Class getPGobject(String type);

    String getJavaClass(int oid) throws SQLException;

    String getTypeForAlias(String alias);

    int getPrecision(int oid, int typmod);

    int getScale(int oid, int typmod);

    boolean isCaseSensitive(int oid);

    boolean isSigned(int oid);

    int getDisplaySize(int oid, int typmod);

    int getMaximumPrecision(int oid);

    boolean requiresQuoting(int oid) throws SQLException;

}
