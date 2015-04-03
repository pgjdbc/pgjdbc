package org.postgresql.jdbc2;

import java.sql.SQLException;
import java.util.Properties;

import org.postgresql.core.BaseConnection;

/**
 * AutoCommit handler
 */
public class AutoCommitHandler {

	/** Top allow row fetching */
	private final boolean	allowRowFetching;
	/** Top allow row locking */
	private final boolean	allowRowLocking;
	/** Connection */
	private BaseConnection	connection;

	/**
	 * Construct the AutoCommitHandler
	 * 
	 * @param _connection The connection
	 * @param _info The url parameters
	 */
	AutoCommitHandler(final BaseConnection _connection, final Properties _info) {
		// -- The associated connection --
		connection = _connection;
		// -- Top allow row locking --
		allowRowLocking = Boolean.parseBoolean(_info.getProperty("allowAutoCommitRowLocking"));
		// -- Top allow row fetching --
		allowRowFetching = Boolean.parseBoolean(_info.getProperty("allowAutoCommitFetch"));
	}

	/**
	 * Test if row fetching enabled
	 * 
	 * @return true if enabled
	 * @throws SQLException
	 */
	final boolean allowRowFetching() throws SQLException {
		// -- Allow row fetching if transaction or if permit in AutoCommit on mode --
		return !connection.getAutoCommit() || allowRowFetching;
	}

	/**
	 * Release of resources
	 */
	final void close() {
		connection = null;
	}

	/**
	 * Handle end of result set
	 * 
	 * @throws SQLException
	 */
	final void handleEndOfResultSet() throws SQLException {
		// -- If AutoCommit on and row locking or row fetching active we are implicitly in transaction so if
		// end of result set we must commit this transaction --
		if (connection.getAutoCommit() && (allowRowLocking || allowRowFetching)) {
			connection.execSQLCommit();
		}
	}

	/**
	 * Handle query exception in AutoCommit on mode
	 * 
	 * @throws SQLException
	 */
	final void handleException() throws SQLException {
		// -- If AutoCommit on and row locking or row fetching active we are implicitly in transaction so if
		// query throw a exception we must rollback this transaction --
		if (connection.getAutoCommit() && (allowRowLocking || allowRowFetching)) {
			connection.execSQLRollback();
		}
	}

	/**
	 * Test if we are in transaction
	 * 
	 * @return true if transaction
	 * @throws SQLException
	 */
	final boolean isTransactional() throws SQLException {
		// -- Transaction if AutoCommit off or if row locking or row fetching permit in AutoCommit on mode --
		return !connection.getAutoCommit() || allowRowLocking || allowRowFetching;
	}
}
