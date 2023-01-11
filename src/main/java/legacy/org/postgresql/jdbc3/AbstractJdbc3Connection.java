/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc3;

import java.util.Properties;
import java.sql.*;

import legacy.org.postgresql.jdbc2.AbstractJdbc2Connection;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

/**
 * This class defines methods of the jdbc3 specification.  This class extends
 * org.postgresql.jdbc2.AbstractJdbc2Connection which provides the jdbc2
 * methods.  The real Connection class (for jdbc3) is org.postgresql.jdbc3.Jdbc3Connection
 */
public abstract class AbstractJdbc3Connection extends AbstractJdbc2Connection
{
    private int rsHoldability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
    private int savepointId = 0;

    protected AbstractJdbc3Connection(String host, int port, String user, String database, Properties info, String url) throws SQLException {
        super(host, port, user, database, info, url);
    }

    /**
     * Changes the holdability of <code>ResultSet</code> objects
     * created using this <code>Connection</code> object to the given
     * holdability.
     *
     * @param holdability a <code>ResultSet</code> holdability constant; one of
     *    <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *    <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @throws SQLException if a database access occurs, the given parameter
     *     is not a <code>ResultSet</code> constant indicating holdability,
     *     or the given holdability is not supported
     * @see #getHoldability
     * @see ResultSet
     * @since 1.4
     */
    public void setHoldability(int holdability) throws SQLException
    {
        checkClosed();

        switch (holdability)
        {
        case ResultSet.CLOSE_CURSORS_AT_COMMIT:
            rsHoldability = holdability;
            break;
        case ResultSet.HOLD_CURSORS_OVER_COMMIT:
            rsHoldability = holdability;
            break;
        default:
            throw new PSQLException(GT.tr("Unknown ResultSet holdability setting: {0}.", new Integer(holdability)),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

    /**
     * Retrieves the current holdability of <code>ResultSet</code> objects
     * created using this <code>Connection</code> object.
     *
     * @return the holdability, one of
     *    <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *    <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @throws SQLException if a database access occurs
     * @see #setHoldability
     * @see ResultSet
     * @since 1.4
     */
    public int getHoldability() throws SQLException
    {
        checkClosed();
        return rsHoldability;
    }

    /**
     * Creates an unnamed savepoint in the current transaction and
     * returns the new <code>Savepoint</code> object that represents it.
     *
     * @return the new <code>Savepoint</code> object
     * @exception SQLException if a database access error occurs
     *     or this <code>Connection</code> object is currently in
     *     auto-commit mode
     * @see Savepoint
     * @since 1.4
     */
    public Savepoint setSavepoint() throws SQLException
    {
        checkClosed();
        if (!haveMinimumServerVersion("8.0"))
            throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."), PSQLState.NOT_IMPLEMENTED);
        if (getAutoCommit())
            throw new PSQLException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
                                    PSQLState.NO_ACTIVE_SQL_TRANSACTION);

        PSQLSavepoint savepoint = new PSQLSavepoint(savepointId++);

        // Note we can't use execSQLUpdate because we don't want
        // to suppress BEGIN.
        Statement stmt = createStatement();
        stmt.executeUpdate("SAVEPOINT " + savepoint.getPGName());
        stmt.close();

        return savepoint;
    }

    /**
     * Creates a savepoint with the given name in the current transaction
     * and returns the new <code>Savepoint</code> object that represents it.
     *
     * @param name a <code>String</code> containing the name of the savepoint
     * @return the new <code>Savepoint</code> object
     * @exception SQLException if a database access error occurs
     *     or this <code>Connection</code> object is currently in
     *     auto-commit mode
     * @see Savepoint
     * @since 1.4
     */
    public Savepoint setSavepoint(String name) throws SQLException
    {
        checkClosed();
        if (!haveMinimumServerVersion("8.0"))
            throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."), PSQLState.NOT_IMPLEMENTED);
        if (getAutoCommit())
            throw new PSQLException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
                                    PSQLState.NO_ACTIVE_SQL_TRANSACTION);

        PSQLSavepoint savepoint = new PSQLSavepoint(name);

        // Note we can't use execSQLUpdate because we don't want
        // to suppress BEGIN.
        Statement stmt = createStatement();
        stmt.executeUpdate("SAVEPOINT " + savepoint.getPGName());
        stmt.close();

        return savepoint;
    }

    /**
     * Undoes all changes made after the given <code>Savepoint</code> object
     * was set.
     * <P>
     * This method should be used only when auto-commit has been disabled.
     *
     * @param savepoint the <code>Savepoint</code> object to roll back to
     * @exception SQLException if a database access error occurs,
     *     the <code>Savepoint</code> object is no longer valid,
     *     or this <code>Connection</code> object is currently in
     *     auto-commit mode
     * @see Savepoint
     * @see #rollback
     * @since 1.4
     */
    public void rollback(Savepoint savepoint) throws SQLException
    {
        checkClosed();
        if (!haveMinimumServerVersion("8.0"))
            throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."), PSQLState.NOT_IMPLEMENTED);

        PSQLSavepoint pgSavepoint = (PSQLSavepoint)savepoint;
        execSQLUpdate("ROLLBACK TO SAVEPOINT " + pgSavepoint.getPGName());
    }


    /**
     * Removes the given <code>Savepoint</code> object from the current
     * transaction. Any reference to the savepoint after it have been removed
     * will cause an <code>SQLException</code> to be thrown.
     *
     * @param savepoint the <code>Savepoint</code> object to be removed
     * @exception SQLException if a database access error occurs or
     *     the given <code>Savepoint</code> object is not a valid
     *     savepoint in the current transaction
     * @since 1.4
     */
    public void releaseSavepoint(Savepoint savepoint) throws SQLException
    {
        checkClosed();
        if (!haveMinimumServerVersion("8.0"))
            throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."), PSQLState.NOT_IMPLEMENTED);

        PSQLSavepoint pgSavepoint = (PSQLSavepoint)savepoint;
        execSQLUpdate("RELEASE SAVEPOINT " + pgSavepoint.getPGName());
        pgSavepoint.invalidate();
    }


    /**
     * Creates a <code>Statement</code> object that will generate
     * <code>ResultSet</code> objects with the given type, concurrency,
     * and holdability.
     * This method is the same as the <code>createStatement</code> method
     * above, but it allows the default result set
     * type, concurrency, and holdability to be overridden.
     *
     * @param resultSetType one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *     <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *     <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.CONCUR_READ_ONLY</code> or
     *     <code>ResultSet.CONCUR_UPDATABLE</code>
     * @param resultSetHoldability one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *     <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @return a new <code>Statement</code> object that will generate
     *     <code>ResultSet</code> objects with the given type,
     *     concurrency, and holdability
     * @exception SQLException if a database access error occurs
     *     or the given parameters are not <code>ResultSet</code>
     *     constants indicating type, concurrency, and holdability
     * @see ResultSet
     * @since 1.4
     */
    public abstract Statement createStatement(int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException;

    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();
        return createStatement(resultSetType, resultSetConcurrency, getHoldability());
    }

    /**
     * Creates a <code>PreparedStatement</code> object that will generate
     * <code>ResultSet</code> objects with the given type, concurrency,
     * and holdability.
     * <P>
     * This method is the same as the <code>prepareStatement</code> method
     * above, but it allows the default result set
     * type, concurrency, and holdability to be overridden.
     *
     * @param sql a <code>String</code> object that is the SQL statement to
     *     be sent to the database; may contain one or more ? IN
     *     parameters
     * @param resultSetType one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *     <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *     <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.CONCUR_READ_ONLY</code> or
     *     <code>ResultSet.CONCUR_UPDATABLE</code>
     * @param resultSetHoldability one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *     <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @return a new <code>PreparedStatement</code> object, containing the
     *     pre-compiled SQL statement, that will generate
     *     <code>ResultSet</code> objects with the given type,
     *     concurrency, and holdability
     * @exception SQLException if a database access error occurs
     *     or the given parameters are not <code>ResultSet</code>
     *     constants indicating type, concurrency, and holdability
     * @see ResultSet
     * @since 1.4
     */
    public abstract PreparedStatement prepareStatement(String sql, int resultSetType,
            int resultSetConcurrency, int resultSetHoldability)
    throws SQLException;

    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();
        return prepareStatement(sql, resultSetType, resultSetConcurrency, getHoldability());
    }


    /**
     * Creates a <code>CallableStatement</code> object that will generate
     * <code>ResultSet</code> objects with the given type and concurrency.
     * This method is the same as the <code>prepareCall</code> method
     * above, but it allows the default result set
     * type, result set concurrency type and holdability to be overridden.
     *
     * @param sql a <code>String</code> object that is the SQL statement to
     *     be sent to the database; may contain on or more ? parameters
     * @param resultSetType one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *     <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *     <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @param resultSetConcurrency one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.CONCUR_READ_ONLY</code> or
     *     <code>ResultSet.CONCUR_UPDATABLE</code>
     * @param resultSetHoldability one of the following <code>ResultSet</code>
     *    constants:
     *     <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *     <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @return a new <code>CallableStatement</code> object, containing the
     *     pre-compiled SQL statement, that will generate
     *     <code>ResultSet</code> objects with the given type,
     *     concurrency, and holdability
     * @exception SQLException if a database access error occurs
     *     or the given parameters are not <code>ResultSet</code>
     *     constants indicating type, concurrency, and holdability
     * @see ResultSet
     * @since 1.4
     */
    public abstract CallableStatement prepareCall(String sql, int resultSetType,
            int resultSetConcurrency,
            int resultSetHoldability) throws SQLException;

    public CallableStatement prepareCall(String sql, int resultSetType,
                                         int resultSetConcurrency) throws SQLException {
        checkClosed();
        return prepareCall(sql, resultSetType, resultSetConcurrency, getHoldability());
    }

    /**
     * Creates a default <code>PreparedStatement</code> object that has
     * the capability to retrieve auto-generated keys. The given constant
     * tells the driver whether it should make auto-generated keys
     * available for retrieval.  This parameter is ignored if the SQL
     * statement is not an <code>INSERT</code> statement.
     * <P>
     * <B>Note:</B> This method is optimized for handling
     * parametric SQL statements that benefit from precompilation. If
     * the driver supports precompilation,
     * the method <code>prepareStatement</code> will send
     * the statement to the database for precompilation. Some drivers
     * may not support precompilation. In this case, the statement may
     * not be sent to the database until the <code>PreparedStatement</code>
     * object is executed. This has no direct effect on users; however, it does
     * affect which methods throw certain SQLExceptions.
     * <P>
     * Result sets created using the returned <code>PreparedStatement</code>
     * object will by default be type <code>TYPE_FORWARD_ONLY</code>
     * and have a concurrency level of <code>CONCUR_READ_ONLY</code>.
     *
     * @param sql an SQL statement that may contain one or more '?' IN
     *    parameter placeholders
     * @param autoGeneratedKeys a flag indicating whether auto-generated keys
     *    should be returned; one of the following <code>Statement</code>
     *    constants:
     *    <code>Statement.RETURN_GENERATED_KEYS</code> or
     *    <code>Statement.NO_GENERATED_KEYS</code>.
     * @return a new <code>PreparedStatement</code> object, containing the
     *     pre-compiled SQL statement, that will have the capability of
     *     returning auto-generated keys
     * @exception SQLException if a database access error occurs
     *     or the given parameter is not a <code>Statement</code>
     *     constant indicating whether auto-generated keys should be
     *     returned
     * @since 1.4
     */
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys)
    throws SQLException
    {
        checkClosed();
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS)
            sql = AbstractJdbc3Statement.addReturning(this, sql, new String[]{"*"}, false);

        PreparedStatement ps = prepareStatement(sql);

        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS)
            ((AbstractJdbc3Statement)ps).wantsGeneratedKeysAlways = true;

        return ps;
    }


    /**
     * Creates a default <code>PreparedStatement</code> object capable
     * of returning the auto-generated keys designated by the given array.
     * This array contains the indexes of the columns in the target
     * table that contain the auto-generated keys that should be made
     * available. This array is ignored if the SQL
     * statement is not an <code>INSERT</code> statement.
     * <P>
     * An SQL statement with or without IN parameters can be
     * pre-compiled and stored in a <code>PreparedStatement</code> object. This
     * object can then be used to efficiently execute this statement
     * multiple times.
     * <P>
     * <B>Note:</B> This method is optimized for handling
     * parametric SQL statements that benefit from precompilation. If
     * the driver supports precompilation,
     * the method <code>prepareStatement</code> will send
     * the statement to the database for precompilation. Some drivers
     * may not support precompilation. In this case, the statement may
     * not be sent to the database until the <code>PreparedStatement</code>
     * object is executed. This has no direct effect on users; however, it does
     * affect which methods throw certain SQLExceptions.
     * <P>
     * Result sets created using the returned <code>PreparedStatement</code>
     * object will by default be type <code>TYPE_FORWARD_ONLY</code>
     * and have a concurrency level of <code>CONCUR_READ_ONLY</code>.
     *
     * @param sql an SQL statement that may contain one or more '?' IN
     *    parameter placeholders
     * @param columnIndexes an array of column indexes indicating the columns
     *    that should be returned from the inserted row or rows
     * @return a new <code>PreparedStatement</code> object, containing the
     *     pre-compiled statement, that is capable of returning the
     *     auto-generated keys designated by the given array of column
     *     indexes
     * @exception SQLException if a database access error occurs
     *
     * @since 1.4
     */
    public PreparedStatement prepareStatement(String sql, int columnIndexes[])
    throws SQLException
    {
        if (columnIndexes == null || columnIndexes.length == 0)
            return prepareStatement(sql);
        
        checkClosed();
        throw new PSQLException(GT.tr("Returning autogenerated keys is not supported."), PSQLState.NOT_IMPLEMENTED);
    }


    /**
     * Creates a default <code>PreparedStatement</code> object capable
     * of returning the auto-generated keys designated by the given array.
     * This array contains the names of the columns in the target
     * table that contain the auto-generated keys that should be returned.
     * This array is ignored if the SQL
     * statement is not an <code>INSERT</code> statement.
     * <P>
     * An SQL statement with or without IN parameters can be
     * pre-compiled and stored in a <code>PreparedStatement</code> object. This
     * object can then be used to efficiently execute this statement
     * multiple times.
     * <P>
     * <B>Note:</B> This method is optimized for handling
     * parametric SQL statements that benefit from precompilation. If
     * the driver supports precompilation,
     * the method <code>prepareStatement</code> will send
     * the statement to the database for precompilation. Some drivers
     * may not support precompilation. In this case, the statement may
     * not be sent to the database until the <code>PreparedStatement</code>
     * object is executed. This has no direct effect on users; however, it does
     * affect which methods throw certain SQLExceptions.
     * <P>
     * Result sets created using the returned <code>PreparedStatement</code>
     * object will by default be type <code>TYPE_FORWARD_ONLY</code>
     * and have a concurrency level of <code>CONCUR_READ_ONLY</code>.
     *
     * @param sql an SQL statement that may contain one or more '?' IN
     *    parameter placeholders
     * @param columnNames an array of column names indicating the columns
     *    that should be returned from the inserted row or rows
     * @return a new <code>PreparedStatement</code> object, containing the
     *     pre-compiled statement, that is capable of returning the
     *     auto-generated keys designated by the given array of column
     *     names
     * @exception SQLException if a database access error occurs
     *
     * @since 1.4
     */
    public PreparedStatement prepareStatement(String sql, String columnNames[])
    throws SQLException
    {
        if (columnNames != null && columnNames.length != 0)
            sql = AbstractJdbc3Statement.addReturning(this, sql, columnNames, true);

        PreparedStatement ps = prepareStatement(sql);

        if (columnNames != null && columnNames.length != 0)
            ((AbstractJdbc3Statement)ps).wantsGeneratedKeysAlways = true;

        return ps;
    }

}


