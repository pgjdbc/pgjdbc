/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2004, PostgreSQL Global Development Group
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *	  $PostgreSQL: pgjdbc/org/postgresql/core/v3/QueryExecutorImpl.java,v 1.12 2004/11/01 20:16:32 jurka Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core.v3;

import org.postgresql.core.*;

import java.util.ArrayList;
import java.util.Vector;
import java.util.HashMap;

import java.lang.ref.*;

import java.io.IOException;
import java.sql.*;
import org.postgresql.Driver;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLWarning;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.postgresql.util.GT;

/**
 * QueryExecutor implementation for the V3 protocol.
 */
public class QueryExecutorImpl implements QueryExecutor {
	public QueryExecutorImpl(ProtocolConnectionImpl protoConnection, PGStream pgStream) {
		this.protoConnection = protoConnection;
		this.pgStream = pgStream;
	}

	//
	// Query parsing
	//

	public Query createSimpleQuery(String sql) {
		return parseQuery(sql, false);
	}

	public Query createParameterizedQuery(String sql) {
		return parseQuery(sql, true);
	}

	private static Query parseQuery(String query, boolean withParameters) {
		// Parse query and find parameter placeholders;
		// also break the query into separate statements.
		
		ArrayList statementList = new ArrayList();
		ArrayList fragmentList = new ArrayList();
		
		boolean inQuotes = false;
		int fragmentStart = 0;

		boolean inSingleQuotes = false;
		boolean inDoubleQuotes = false;

		for (int i = 0; i < query.length(); ++i)
		{
			char c = query.charAt(i);

			switch (c) {
			case '\\':
				if (inSingleQuotes)
					++i; // Skip one character.
				break;

			case '\'':
				inSingleQuotes = !inDoubleQuotes && !inSingleQuotes;
				break;

			case '"':
				inDoubleQuotes = !inSingleQuotes && !inDoubleQuotes;
				break;

			case '?':
				if (withParameters && !inSingleQuotes && !inDoubleQuotes) {
					fragmentList.add(query.substring(fragmentStart, i));
					fragmentStart = i + 1;
				}
				break;

			case ';':
				if (!inSingleQuotes && !inDoubleQuotes) {
					fragmentList.add(query.substring(fragmentStart, i));
					fragmentStart = i + 1;
					if (fragmentList.size() > 1 || ((String)fragmentList.get(0)).trim().length() > 0)
						statementList.add(fragmentList.toArray(new String[fragmentList.size()]));
					fragmentList.clear();
				}
				break;

			default:
				break;
			}
		}

		fragmentList.add(query.substring(fragmentStart));
		if (fragmentList.size() > 1 || ((String)fragmentList.get(0)).trim().length() > 0)
			statementList.add(fragmentList.toArray(new String[fragmentList.size()]));

		if (statementList.isEmpty())  // Empty query.
			return EMPTY_QUERY;

		if (statementList.size() == 1) {
			// Only one statement.
			return new SimpleQuery((String[]) statementList.get(0));
		}

		// Multiple statements.
		SimpleQuery[] subqueries = new SimpleQuery[statementList.size()];
		int[] offsets = new int[statementList.size()];
		int offset = 0;
		for (int i = 0; i < statementList.size(); ++i) {
			String[] fragments = (String[]) statementList.get(i);
			offsets[i] = offset;
			subqueries[i] = new SimpleQuery(fragments);
			offset += fragments.length - 1;
		}

		return new CompositeQuery(subqueries, offsets);
	}

	//
	// Query execution
	//

	public synchronized void execute(Query query,
									 ParameterList parameters,
									 ResultHandler handler,
									 int maxRows,
									 int fetchSize,
									 int flags) throws SQLException
	{
		if (Driver.logDebug) {
			Driver.debug("simple execute, handler=" + handler +
						 ", maxRows=" + maxRows + ", fetchSize=" + fetchSize + ", flags=" + flags);
		}

		if (parameters == null)
			parameters = SimpleQuery.NO_PARAMETERS;

		// Check parameters are all set..
		((V3ParameterList)parameters).checkAllParametersSet();

		try {
			try {
				handler = sendQueryPreamble(handler, flags);		
				sendQuery((V3Query)query, (V3ParameterList)parameters, maxRows, fetchSize, flags);		
				sendSync();
				processResults(handler, flags);
			} catch (PGBindException se) {
				// There are three causes of this error, an
				// invalid total Bind message length, a
				// BinaryStream that cannot provide the amount
				// of data claimed by the length arugment, and
				// a BinaryStream that throws an Exception
				// when reading.
				//
				// We simply do not send the Execute message
				// so we can just continue on as if nothing
				// has happened.  Perhaps we need to
				// introduce an error here to force the
				// caller to rollback if there is a
				// transaction in progress?
				//
				sendSync();
				processResults(handler, flags);
				handler.handleError(new PSQLException(GT.tr("Unable to bind parameter values for statement."), PSQLState.INVALID_PARAMETER_VALUE, se.getIOException()));
			}
		} catch (IOException e) {
			protoConnection.close();
			handler.handleError(new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
		}

		handler.handleCompletion();		
	}

	// Deadlock avoidance:
	//
	// It's possible for the send and receive streams to get "deadlocked" against each other since
	// we do not have a separate thread. The scenario is this: we have two streams:
	//
	//   driver -> TCP buffering -> server
	//   server -> TCP buffering -> driver
	//
	// The server behaviour is roughly:
	//  while true:
	//   read message
	//   execute message
	//   write results
	//
	// If the server -> driver stream has a full buffer, the write will block.
	// If the driver is still writing when this happens, and the driver -> server
	// stream also fills up, we deadlock: the driver is blocked on write() waiting
	// for the server to read some more data, and the server is blocked on write()
	// waiting for the driver to read some more data.
	//
	// To avoid this, we guess at how many queries we can send before the server ->
	// driver stream's buffer is full (MAX_BUFFERED_QUERIES). This is the point where
	// the server blocks on write and stops reading data. If we reach this point, we
	// force a Sync message and read pending data from the server until ReadyForQuery,
	// then go back to writing more queries unless we saw an error.
	//
	// This is not 100% reliable -- it's only done in the batch-query case and only
	// at a reasonably high level (per query, not per message), and it's only an estimate
	// -- so it might break. To do it correctly in all cases would seem to require a
	// separate send or receive thread as we can only do the Sync-and-read-results
	// operation at particular points, and also as we don't really know how much data
	// the server is sending.

	// Assume 64k server->client buffering and 250 bytes response per query (conservative).
	private static final int MAX_BUFFERED_QUERIES = (64000 / 250);

	// Helper handler that tracks error status.
	private static class ErrorTrackingResultHandler implements ResultHandler {
		private final ResultHandler delegateHandler;
		private boolean sawError = false;

		ErrorTrackingResultHandler(ResultHandler delegateHandler) { 
			this.delegateHandler = delegateHandler;
		}
															   
		public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
			delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
		}
		
		public void handleCommandStatus(String status, int updateCount, long insertOID) {
			delegateHandler.handleCommandStatus(status, updateCount, insertOID);
		}
				
		public void handleWarning(SQLWarning warning) {
			delegateHandler.handleWarning(warning);
		}
		
		public void handleError(SQLException error) {
			sawError = true;
			delegateHandler.handleError(error);
		}
				
		public void handleCompletion() throws SQLException {
			delegateHandler.handleCompletion();
		}

		boolean hasErrors() {
			return sawError;
		}
	}

	public synchronized void execute(Query[] queries,
									 ParameterList[] parameterLists,
									 ResultHandler handler,
									 int maxRows,
									 int fetchSize,
									 int flags) throws SQLException
	{
		if (Driver.logDebug) {
			Driver.debug("batch execute " + queries.length + " queries, handler=" + handler +
						 ", maxRows=" + maxRows + ", fetchSize=" + fetchSize + ", flags=" + flags);
		}

		// Check parameters and resolve OIDs.
		for (int i = 0; i < parameterLists.length; ++i) {
			if (parameterLists[i] != null)
				((V3ParameterList)parameterLists[i]).checkAllParametersSet();
		}		

		try {
			int queryCount = 0;

			handler = sendQueryPreamble(handler, flags);
			ErrorTrackingResultHandler trackingHandler = new ErrorTrackingResultHandler(handler);

			for (int i = 0; i < queries.length; ++i) {
				++queryCount;
				if (queryCount >= MAX_BUFFERED_QUERIES) {
					sendSync();
					processResults(trackingHandler, flags);
					
					// If we saw errors, don't send anything more.
					if (trackingHandler.hasErrors())
						break;
					
					queryCount = 0;
				}

				V3Query query = (V3Query)queries[i];
				V3ParameterList parameters = (V3ParameterList)parameterLists[i];
				if (parameters == null)
					parameters = SimpleQuery.NO_PARAMETERS;
				sendQuery(query, parameters, maxRows, fetchSize, flags);
			}
			
			if (!trackingHandler.hasErrors()) {
				sendSync();
				processResults(handler, flags);
			}
		} catch (IOException e) {
			protoConnection.close();
			handler.handleError(new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
		}

		handler.handleCompletion();
	}

	private ResultHandler sendQueryPreamble(final ResultHandler delegateHandler, int flags) throws IOException {
		// First, send CloseStatements for finalized SimpleQueries that had statement names assigned.
		processDeadParsedQueries();
		processDeadPortals();
		
		// Send BEGIN on first statement in transaction.
		if ((flags & QueryExecutor.QUERY_SUPPRESS_BEGIN) != 0 || 
			protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
			return delegateHandler;

		sendOneQuery(beginTransactionQuery, SimpleQuery.NO_PARAMETERS, 0, 0, QueryExecutor.QUERY_NO_METADATA);
		
		// Insert a handler that intercepts the BEGIN.
		return new ResultHandler() {
				private boolean sawBegin = false;
				
				public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
					if (sawBegin)
						delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
				}
				
				public void handleCommandStatus(String status, int updateCount, long insertOID) {
					if (!sawBegin) {
						sawBegin = true;
						if (!status.equals("BEGIN"))
							handleError(new SQLException(GT.tr("Expected command status BEGIN, got {0}.", status)));
					} else {
						delegateHandler.handleCommandStatus(status, updateCount, insertOID);
					}
				}
				
				public void handleWarning(SQLWarning warning) {
					delegateHandler.handleWarning(warning);
				}
				
				public void handleError(SQLException error) {
					delegateHandler.handleError(error);
				}
				
				public void handleCompletion() throws SQLException{
					delegateHandler.handleCompletion();
				}
			};
	}

	//
	// Fastpath
	//

	public synchronized byte[] fastpathCall(int fnid, ParameterList parameters, boolean suppressBegin) throws SQLException {
		if (protoConnection.getTransactionState() == ProtocolConnection.TRANSACTION_IDLE && !suppressBegin) {

			if (Driver.logDebug)
				Driver.debug("Issuing BEGIN before fastpath call.");

			ResultHandler handler = new ResultHandler() {
					private boolean sawBegin = false;
					private SQLException sqle = null;

					public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
					}

					public void handleCommandStatus(String status, int updateCount, long insertOID) {
						if (!sawBegin) {
							if (!status.equals("BEGIN"))
								handleError(new SQLException(GT.tr("Expected command status BEGIN, got {0}.", status)));
							sawBegin = true;
						} else {
							handleError(new SQLException(GT.tr("Unexpected command status: {0}.", status)));
						}
					}

					public void handleWarning(SQLWarning warning) {
						// we don't want to ignore warnings and it would be tricky
						// to chain them back to the connection, so since we don't
						// expect to get them in the first place, we just consider
						// them errors.
						handleError(warning);
					}

					public void handleError(SQLException error) {
						if (sqle == null) {
							sqle = error;
						} else {
							sqle.setNextException(error);
						}
					}

					public void handleCompletion() throws SQLException{
						if (sqle != null)
							throw sqle;
					}
				};

			try {
				sendOneQuery(beginTransactionQuery, SimpleQuery.NO_PARAMETERS, 0, 0, QueryExecutor.QUERY_NO_METADATA);
				sendSync();
				processResults(handler, 0);
			} catch (IOException ioe) {
				throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
			}
		}

		try {
			sendFastpathCall(fnid, (SimpleParameterList)parameters);
			return receiveFastpathResult();
		} catch (IOException ioe) {
			protoConnection.close();
			throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
		}
	}

	public ParameterList createFastpathParameters(int count) {
		return new SimpleParameterList(count);
	}

	private void sendFastpathCall(int fnid, SimpleParameterList params) throws SQLException, IOException {
		if (Driver.logDebug)
			Driver.debug(" FE=> FunctionCall(" + fnid + ", " + params.getParameterCount() + " params)");

		//
		// Total size = 4 (length)
		//            + 4 (function OID)
		//            + 2 (format code count) + N * 2 (format codes)
		//            + 2 (parameter count) + encodedSize (parameters)
		//            + 2 (result format)

		int paramCount = params.getParameterCount();
		int encodedSize = 0;
		for (int i = 1; i <= paramCount; ++i) {
			if (params.isNull(i))
				encodedSize += 4;
			else
				encodedSize += 4 + params.getV3Length(i);
		}


		pgStream.SendChar('F');
		pgStream.SendInteger4(4 + 4 + 2 + 2 * paramCount + 2 + encodedSize + 2);
		pgStream.SendInteger4(fnid);
		pgStream.SendInteger2(paramCount);
		for (int i = 1; i <= paramCount; ++i)
			pgStream.SendInteger2(params.isBinary(i) ? 1 : 0);
		pgStream.SendInteger2(paramCount);
		for (int i = 1; i <= paramCount; i++) {
			if (params.isNull(i)) {
				pgStream.SendInteger4(-1);
			} else {
				pgStream.SendInteger4(params.getV3Length(i));   // Parameter size
				params.writeV3Value(i, pgStream);
			}
		}
		pgStream.SendInteger2(1); // Binary result format
		pgStream.flush();
	}			

	private byte[] receiveFastpathResult() throws IOException, SQLException {
		boolean endQuery = false;
		SQLException error = null;
		byte[] returnValue = null;

		while (!endQuery) {
			int c = pgStream.ReceiveChar();
			switch (c) {
			case 'A':	// Asynchronous Notify
				receiveAsyncNotify();
				break;

			case 'E':	// Error Response (response to pretty much everything; backend then skips until Sync)				
				SQLException newError = receiveErrorResponse();
				if (error == null)
					error = newError;
				else
					error.setNextException(newError);
				// keep processing
				break;

			case 'N':	// Notice Response (warnings / info)
				SQLWarning warning = receiveNoticeResponse();
				protoConnection.addWarning(warning);
				break;

			case 'Z':   // Ready For Query (eventual response to Sync)
				receiveRFQ();
				endQuery = true;
				break;

			case 'V': // FunctionCallResponse
				int msgLen = pgStream.ReceiveIntegerR(4);
				int valueLen = pgStream.ReceiveIntegerR(4);
				
				if (Driver.logDebug)
					Driver.debug(" <=BE FunctionCallResponse(" + valueLen + " bytes)");

				if (valueLen != -1) {
					byte buf[] = new byte[valueLen];
					pgStream.Receive(buf, 0, valueLen);
					returnValue = buf;
				}

				break;

			default:
				throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);
			}
			
		}
		
		// did we get an error during this query?
		if (error != null)
			throw error;
		
		return returnValue;
	}

	/*
	 * Send a query to the backend.
	 */
	private void sendQuery(V3Query query, V3ParameterList parameters, int maxRows, int fetchSize, int flags) throws IOException, SQLException {
		// Now the query itself.
		SimpleQuery[] subqueries = query.getSubqueries();
		SimpleParameterList[] subparams  = parameters.getSubparams();

		if (subqueries == null) {
			sendOneQuery((SimpleQuery)query, (SimpleParameterList)parameters, maxRows, fetchSize, flags);
		} else {
			for (int i = 0; i < subqueries.length; ++i) {
				// In the situation where parameters is already
				// NO_PARAMETERS it cannot know the correct
				// number of array elements to return in the
				// above call to getSubparams(), so it must
				// return null which we check for here.
				//
				SimpleParameterList subparam = SimpleQuery.NO_PARAMETERS;
				if (subparams != null) {
					subparam = subparams[i];
				}
				sendOneQuery(subqueries[i], subparam, maxRows, fetchSize, flags);
			}
		}
	}

	//
	// Message sending
	//

	private void sendSync() throws IOException {
		if (Driver.logDebug)
			Driver.debug(" FE=> Sync");

		pgStream.SendChar('S');     // Sync
		pgStream.SendInteger4(4); // Length
		pgStream.flush();
	}

	private void sendParse(SimpleQuery query, SimpleParameterList params, boolean oneShot) throws IOException {
		// Already parsed, or we have a Parse pending?
		if (query.getStatementName() != null)
			return;
		
		String statementName = null;
		if (!oneShot) {
			// Generate a statement name to use.
			statementName = "S_" + (nextUniqueID++);
		}

		query.setStatementName(statementName);  // nb: might null it out; this is a deliberate safety net.
		byte[] encodedStatementName = query.getEncodedStatementName();

		String[] fragments = query.getFragments();

		if (Driver.logDebug) {
			StringBuffer sbuf = new StringBuffer(" FE=> Parse(stmt=" + statementName + ",query=\"");
			for (int i = 0; i < fragments.length; ++i) {
				if (i > 0)
					sbuf.append("$" + i);
				sbuf.append(fragments[i]);
			}
			sbuf.append("\",oids={");
			for (int i = 1; i <= params.getParameterCount(); ++i) {
				if (i != 1)
					sbuf.append(",");
				sbuf.append(""+params.getTypeOID(i));
			}
			sbuf.append("})");
			Driver.debug(sbuf.toString());
		}

		//
		// Send Parse.
		//

		byte[][] parts = new byte[fragments.length * 2 - 1][];
		int j = 0;
		int encodedSize = 0;

		// Total size = 4 (size field)
		//            + N + 1 (statement name, zero-terminated) 
		//            + N + 1 (query, zero terminated) 
		//            + 2 (parameter count) + N * 4 (parameter types)
		// original query: "frag0 ? frag1 ? frag2"
		// fragments: { "frag0", "frag1", "frag2" }
		// output: "frag0 $1 frag1 $2 frag2"
		for (int i = 0; i < fragments.length; ++i) {
			if (i != 0) {
				parts[j] = Utils.encodeUTF8("$" + i);
				encodedSize += parts[j].length;
				++j;
			}

			parts[j] = Utils.encodeUTF8(fragments[i]);
			encodedSize += parts[j].length;
			++j;
		}

		encodedSize = 4
			+ (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
			+ encodedSize + 1
			+ 2 + 4 * params.getParameterCount();

		pgStream.SendChar('P'); // Parse
		pgStream.SendInteger4(encodedSize);
		if (encodedStatementName != null)
			pgStream.Send(encodedStatementName);
		pgStream.SendChar(0);   // End of statement name
		for (int i = 0; i < parts.length; ++i) { // Query string
			pgStream.Send(parts[i]);
		}
		pgStream.SendChar(0);       // End of query string.
		pgStream.SendInteger2(params.getParameterCount());       // # of parameter types specified
		for (int i = 1; i <= params.getParameterCount(); ++i)
			pgStream.SendInteger4(params.getTypeOID(i));
		
		pendingParseQueue.add(query);
	}

	private void sendBind(SimpleQuery query, SimpleParameterList params, Portal portal) throws IOException {
		//
		// Send Bind.
		//
		
		String statementName = query.getStatementName();
		byte[] encodedStatementName = query.getEncodedStatementName();
		byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());

		if (Driver.logDebug) {
			StringBuffer sbuf = new StringBuffer(" FE=> Bind(stmt=" + statementName + ",portal=" + portal);
			for (int i = 1; i <= params.getParameterCount(); ++i) {
				sbuf.append(",$" + i + "=<" + params.toString(i) + ">");
			}
			sbuf.append(")");
			Driver.debug(sbuf.toString());
		}

		// Total size = 4 (size field) + N + 1 (destination portal)
		//            + N + 1 (statement name)
		//            + 2 (param format code count) + N * 2 (format codes)
		//            + 2 (param value count) + N (encoded param value size)
		//            + 2 (result format code count, 0)
		long encodedSize = 0;
		for (int i = 1; i <= params.getParameterCount(); ++i) {
			if (params.isNull(i))
				encodedSize += 4;
			else
				encodedSize += (long)4 + params.getV3Length(i);
		}

		encodedSize = 4
			+ (encodedPortalName == null ? 0 : encodedPortalName.length) + 1
			+ (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
			+ 2 + params.getParameterCount()*2
			+ 2 + encodedSize
			+ 2;

		// backend's MaxAllocSize is the largest message that can
		// be received from a client.  If we have a bigger value
		// from either very large parameters or incorrent length
		// descriptions of setXXXStream we do not send the bind
		// messsage.
		//
		if (encodedSize > 0x3fffffff) {
			throw new PGBindException(new IOException(GT.tr("Bind message length {0} too long.  This can be caused by very large or incorrect length specifications on InputStream parameters.", new Long(encodedSize))));
		}

		pgStream.SendChar('B');                  // Bind
		pgStream.SendInteger4((int)encodedSize);      // Message size
		if (encodedPortalName != null)           
			pgStream.Send(encodedPortalName);    // Destination portal name.
		pgStream.SendChar(0);                    // End of portal name.
		if (encodedStatementName != null)
			pgStream.Send(encodedStatementName); // Source statement name.
		pgStream.SendChar(0);                    // End of statement name.

		pgStream.SendInteger2(params.getParameterCount());      // # of parameter format codes
		for (int i = 1; i <= params.getParameterCount(); ++i)
			pgStream.SendInteger2(params.isBinary(i) ? 1 : 0);  // Parameter format code
			
		pgStream.SendInteger2(params.getParameterCount());      // # of parameter values

		// If an error occurs when reading a stream we have to
		// continue pumping out data to match the length we
		// said we would.  Once we've done that we throw
		// this exception.  Multiple exceptions can occur and
		// it really doesn't matter which one is reported back
		// to the caller.
		//
		PGBindException bindException = null;
		
		for (int i = 1; i <= params.getParameterCount(); ++i) {
			if (params.isNull(i))
				pgStream.SendInteger4(-1);                      // Magic size of -1 means NULL
			else {
				pgStream.SendInteger4(params.getV3Length(i));   // Parameter size
				try {
					params.writeV3Value(i, pgStream);                 // Parameter value
				} catch(PGBindException be) {
					bindException = be;
				}
			}
		}

		pgStream.SendChar(0);       // # of result format codes (0)
		pgStream.SendChar(0);       //  (...)

		pendingBindQueue.add(portal);

		if (bindException != null) {
			throw bindException;
		}
	}

	private void sendDescribe(Portal portal) throws IOException {
		//
		// Send Describe.
		//

		if (Driver.logDebug) {
			Driver.debug(" FE=> Describe(portal=" + portal + ")");
		}

		byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());

		// Total size = 4 (size field) + 1 (describe type, 'P') + N + 1 (portal name)
		int encodedSize = 4 + 1 + (encodedPortalName == null ? 0 : encodedPortalName.length) + 1;

		pgStream.SendChar('D');               // Describe
		pgStream.SendInteger4(encodedSize); // message size
		pgStream.SendChar('P');               // Describe (Portal)
		if (encodedPortalName != null)
			pgStream.Send(encodedPortalName); // portal name to close
		pgStream.SendChar(0);                 // end of portal name
	}

	private void sendExecute(Query query, Portal portal, int limit) throws IOException {
		//
		// Send Execute.
		//
		
		if (Driver.logDebug) {
			Driver.debug(" FE=> Execute(portal=" + portal + ",limit=" + limit + ")");
		}

		byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());
		int encodedSize = (encodedPortalName == null ? 0 : encodedPortalName.length);

		// Total size = 4 (size field) + 1 + N (source portal) + 4 (max rows)
		pgStream.SendChar('E');              // Execute
		pgStream.SendInteger4(4 + 1 + encodedSize + 4);  // message size
		if (encodedPortalName != null)
			pgStream.Send(encodedPortalName); // portal name
		pgStream.SendChar(0);                 // portal name terminator
		pgStream.SendInteger4(limit);       // row limit

		pendingExecuteQueue.add(new Object[] { query, portal });
	}

	private void sendClosePortal(String portalName) throws IOException {
		//
		// Send Close.
		//
		
		if (Driver.logDebug) {
			Driver.debug(" FE=> ClosePortal(" + portalName + ")");
		}

		byte[] encodedPortalName = (portalName == null ? null : Utils.encodeUTF8(portalName));
		int encodedSize = (encodedPortalName == null ? 0 : encodedPortalName.length);

		// Total size = 4 (size field) + 1 (close type, 'P') + 1 + N (portal name)
		pgStream.SendChar('C');              // Close
		pgStream.SendInteger4(4 + 1 + 1 + encodedSize);  // message size
		pgStream.SendChar('P');              // Close (Portal)
		if (encodedPortalName != null)
			pgStream.Send(encodedPortalName);
		pgStream.SendChar(0);                // unnamed portal
	}

	private void sendCloseStatement(String statementName) throws IOException {
		//
		// Send Close.
		//
		
		if (Driver.logDebug) {
			Driver.debug(" FE=> CloseStatement(" + statementName + ")");
		}

		byte[] encodedStatementName = Utils.encodeUTF8(statementName);

		// Total size = 4 (size field) + 1 (close type, 'S') + N + 1 (statement name)
		pgStream.SendChar('C');              // Close
		pgStream.SendInteger4(4 + 1 + encodedStatementName.length + 1);  // message size
		pgStream.SendChar('S');              // Close (Statement)
		pgStream.Send(encodedStatementName); // statement to close
		pgStream.SendChar(0);                // statement name terminator
	}

	// sendOneQuery sends a single statement via the extended query protocol.
	// Per the FE/BE docs this is essentially the same as how a simple query runs
	// (except that it generates some extra acknowledgement messages, and we
	// can send several queries before doing the Sync)
	//
	//   Parse     S_n from "query string with parameter placeholders"; skipped if already done previously or if oneshot
	//   Bind      C_n from S_n plus parameters (or from unnamed statement for oneshot queries)
	//   Describe  C_n; skipped if caller doesn't want metadata
	//   Execute   C_n with maxRows limit; maxRows = 1 if caller doesn't want results
	// (above repeats once per call to sendOneQuery)
	//   Sync      (sent by caller)
	//
	private void sendOneQuery(SimpleQuery query, SimpleParameterList params, int maxRows, int fetchSize, int flags) throws IOException {
		// nb: if we decide to use a portal (usePortal == true) we must also use a named statement
		// (oneShot == false) as otherwise the portal will be closed under us unexpectedly when
		// the unnamed statement is next reused.

		boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;
		boolean noMeta  = (flags & QueryExecutor.QUERY_NO_METADATA) != 0;
		boolean usePortal = (flags & QueryExecutor.QUERY_FORWARD_CURSOR) != 0 && !noResults && !noMeta && fetchSize > 0;
		boolean oneShot = (flags & QueryExecutor.QUERY_ONESHOT) != 0 && !usePortal;

		// Work out how many rows to fetch in this pass.

		int rows;
		if (noResults) {
			rows = 1;             // We're discarding any results anyway, so limit data transfer to a minimum
		} else if (!usePortal) {
			rows = maxRows;       // Not using a portal -- fetchSize is irrelevant
		} else if (maxRows != 0 && fetchSize > maxRows) {
			rows = maxRows;       // fetchSize > maxRows, use maxRows (nb: fetchSize cannot be 0 if usePortal == true)
		} else {
			rows = fetchSize;     // maxRows > fetchSize
		}

		sendParse(query, params, oneShot);

		// Construct a new portal if needed.
		Portal portal = null;
		if (usePortal) {
			String portalName = "C_" + (nextUniqueID++);
			portal = new Portal(query, portalName);
		}

		sendBind(query, params, portal);
		if (!noMeta)
			sendDescribe(portal);

		sendExecute(query, portal, rows);
	}		

	//
	// Garbage collection of parsed statements.
	//
	// When a statement is successfully parsed, registerParsedQuery is called.
	// This creates a PhantomReference referring to the "owner" of the statement
	// (the originating Query object) and inserts that reference as a key in
	// parsedQueryMap. The values of parsedQueryMap are the corresponding allocated
	// statement names. The originating Query object also holds a reference to the
	// PhantomReference.
	//
	// When the owning Query object is closed, it enqueues and clears the associated
	// PhantomReference.
	//
	// If the owning Query object becomes unreachable (see java.lang.ref javadoc) before
	// being closed, the corresponding PhantomReference is enqueued on
	// parsedQueryCleanupQueue. In the Sun JVM, phantom references are only enqueued
	// when a GC occurs, so this is not necessarily prompt but should eventually happen.
	//
	// Periodically (currently, just before query execution), the parsedQueryCleanupQueue
	// is polled. For each enqueued PhantomReference we find, we remove the corresponding
	// entry from parsedQueryMap, obtaining the name of the underlying statement in the
	// process. Then we send a message to the backend to deallocate that statement.
	//

	private final HashMap parsedQueryMap = new HashMap();
	private final ReferenceQueue parsedQueryCleanupQueue = new ReferenceQueue();

	private void registerParsedQuery(SimpleQuery query) {
		String statementName = query.getStatementName();
		if (statementName == null)
			return;

		PhantomReference cleanupRef = new PhantomReference(query, parsedQueryCleanupQueue);
		parsedQueryMap.put(cleanupRef, statementName);
		query.setCleanupRef(cleanupRef);
	}

	private void processDeadParsedQueries() throws IOException {
		PhantomReference deadQuery;
		while ((deadQuery = (PhantomReference)parsedQueryCleanupQueue.poll()) != null) {
			String statementName = (String)parsedQueryMap.remove(deadQuery);
			sendCloseStatement(statementName);
			deadQuery.clear();
		}
	}

	//
	// Essentially the same strategy is used for the cleanup of portals.
	// Note that each Portal holds a reference to the corresponding Query
	// that generated it, so the Query won't be collected (and the statement
	// closed) until all the Portals are, too. This is required by the mechanics
	// of the backend protocol: when a statement is closed, all dependent portals
	// are also closed.
	//

	private final HashMap openPortalMap = new HashMap();
	private final ReferenceQueue openPortalCleanupQueue = new ReferenceQueue();

	private void registerOpenPortal(Portal portal) {
		if (portal == null)
			return; // Using the unnamed portal.

		String portalName = portal.getPortalName();
		PhantomReference cleanupRef = new PhantomReference(portal, openPortalCleanupQueue);
		openPortalMap.put(cleanupRef, portalName);
		portal.setCleanupRef(cleanupRef);
	}

	private void processDeadPortals() throws IOException {
		PhantomReference deadPortal;
		while ((deadPortal = (PhantomReference)openPortalCleanupQueue.poll()) != null) {
			String portalName = (String)openPortalMap.remove(deadPortal);
			sendClosePortal(portalName);
			deadPortal.clear();
		}
	}

	protected void processResults(ResultHandler handler, int flags) throws IOException {
		boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;

		Field[] fields = null;
		Vector tuples = null;

		int len;
		int c;
		boolean endQuery = false;

		int parseIndex = 0;
		int bindIndex = 0;
		int executeIndex = 0;

		while (!endQuery) {
			c = pgStream.ReceiveChar();
			switch (c) {
			case 'A':	// Asynchronous Notify
				receiveAsyncNotify();
				break;

			case '1':   // Parse Complete (response to Parse)
				pgStream.ReceiveIntegerR(4); // len, discarded

				SimpleQuery parsedQuery = (SimpleQuery)pendingParseQueue.get(parseIndex++);
				if (Driver.logDebug)
					Driver.debug(" <=BE ParseComplete [" + parsedQuery.getStatementName() + "]");				

				registerParsedQuery(parsedQuery);
				break;

			case '2':   // Bind Complete  (response to Bind)
				pgStream.ReceiveIntegerR(4); // len, discarded

				Portal boundPortal = (Portal)pendingBindQueue.get(bindIndex++);
				if (Driver.logDebug)
					Driver.debug(" <=BE BindComplete [" + boundPortal + "]");
				
				registerOpenPortal(boundPortal);
				break;

			case '3':   // Close Complete (response to Close)
				pgStream.ReceiveIntegerR(4); // len, discarded
				if (Driver.logDebug)
					Driver.debug(" <=BE CloseComplete");
				break;

			case 'n':   // No Data        (response to Describe)
				pgStream.ReceiveIntegerR(4); // len, discarded
				if (Driver.logDebug)
					Driver.debug(" <=BE NoData");
				break;

			case 's':   // Portal Suspended (end of Execute)
				// nb: this appears *instead* of CommandStatus.
				// Must be a SELECT if we suspended, so don't worry about it.

				pgStream.ReceiveIntegerR(4); // len, discarded
				if (Driver.logDebug)
					Driver.debug(" <=BE PortalSuspended");

				{
					Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
					Query currentQuery = (Query)executeData[0];
					Portal currentPortal = (Portal)executeData[1];
					handler.handleResultRows(currentQuery, fields, tuples, currentPortal);
				}

				fields = null;
				tuples = null;
				break;

			case 'C':	// Command Status (end of Execute)
				// Handle status.
				String status = receiveCommandStatus();

				{
					Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
					Query currentQuery = (Query)executeData[0];
					Portal currentPortal = (Portal)executeData[1];

					if (fields != null || tuples != null) { // There was a resultset.
						handler.handleResultRows(currentQuery, fields, tuples, null);
					} else {
						interpretCommandStatus(status, handler);
					}

					if (currentPortal != null)
						currentPortal.close();
				}
				break;

			case 'D':	// Data Transfer (ongoing Execute response)
				Object tuple = pgStream.ReceiveTupleV3();
				if (!noResults) {
					if (tuples == null)
						tuples = new Vector();
					tuples.addElement(tuple);
				}

				if (Driver.logDebug)
					Driver.debug(" <=BE DataRow");

				break;

			case 'E':	// Error Response (response to pretty much everything; backend then skips until Sync)
				SQLException error = receiveErrorResponse();
				handler.handleError(error);

				// keep processing
				break;

			case 'I':	// Empty Query (end of Execute)
				pgStream.ReceiveIntegerR(4);

				if (Driver.logDebug)
					Driver.debug(" <=BE EmptyQuery");

				{
					Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
					Query currentQuery = (Query)executeData[0];
					Portal currentPortal = (Portal)executeData[1];
					handler.handleCommandStatus("EMPTY", 0, 0);
					if (currentPortal != null)
						currentPortal.close();
				}

				break;

			case 'N':	// Notice Response
				SQLWarning warning = receiveNoticeResponse();
				handler.handleWarning(warning);
				break;

			case 'S':   // Parameter Status
				{
					int l_len = pgStream.ReceiveIntegerR(4);
					String name = pgStream.ReceiveString();
					String value = pgStream.ReceiveString();
					if (Driver.logDebug)
						Driver.debug(" <=BE ParameterStatus(" + name + " = " + value + ")");

					if (name.equals("client_encoding") && !value.equals("UNICODE")) {
						protoConnection.close(); // we're screwed now; we can't trust any subsequent string.
						handler.handleError(new PSQLException(GT.tr("The server's client_encoding parameter was changed to {0}. The JDBC driver requires client_encoding to be UNICODE for correct operation.", value), PSQLState.CONNECTION_FAILURE));
						endQuery = true;
					}

					if (name.equals("DateStyle") && !value.startsWith("ISO,")) {
						protoConnection.close(); // we're screwed now; we can't trust any subsequent date.
						handler.handleError(new PSQLException(GT.tr("The server's DateStyle parameter was changed to {0}. The JDBC driver requires DateStyle to begin with ISO for correct operation.", value), PSQLState.CONNECTION_FAILURE));
						endQuery = true;
					}
				}
				break;

			case 'T':	// Row Description (response to Describe)
				fields = receiveFields();
				tuples = new Vector();
				break;

			case 'Z':   // Ready For Query (eventual response to Sync)
				receiveRFQ();
				endQuery = true;

				// Reset the statement name of Parses that failed.
				while (parseIndex < pendingParseQueue.size()) {
					SimpleQuery failedQuery = (SimpleQuery)pendingParseQueue.get(parseIndex++);
					failedQuery.setStatementName(null);
				}

				pendingParseQueue.clear();   // No more ParseComplete messages expected.		
				pendingBindQueue.clear();    // No more BindComplete messages expected.		
				pendingExecuteQueue.clear(); // No more query executions expected.
				break;

			case 'G': // CopyInResponse
			case 'H': // CopyOutResponse
			case 'c': // CopyDone
			case 'd': // CopyData
				{
					// COPY FROM STDIN / COPY TO STDOUT, neither of which are currently
					// supported.

					// CopyInResponse can only occur in response to an Execute we sent.
					// Every Execute we send is followed by either a Bind or a ClosePortal,
					// so we don't need to send a CopyFail; the server will fail the copy
					// automatically when it sees the next message.

					int l_len = pgStream.ReceiveIntegerR(4);
					/* discard */ pgStream.Receive(l_len);

					handler.handleError(new PSQLException(GT.tr("The driver currently does not support COPY operations."), PSQLState.NOT_IMPLEMENTED));
				}
				break;

			default:
				throw new IOException("Unexpected packet type: " + c);
			}
			
		}
	}

	public synchronized void fetch(ResultCursor cursor, ResultHandler handler, int fetchSize) throws SQLException {
		final Portal portal = (Portal)cursor;

		// Insert a ResultHandler that turns bare command statuses into empty datasets
		// (if the fetch returns no rows, we see just a CommandStatus..)
		final ResultHandler delegateHandler = handler;
		handler = new ResultHandler() {
				public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
					delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
				}

				public void handleCommandStatus(String status, int updateCount, long insertOID) {
					handleResultRows(portal.getQuery(), null, new Vector(), null);
				}

				public void handleWarning(SQLWarning warning) {
					delegateHandler.handleWarning(warning);
				}
				
				public void handleError(SQLException error) {
					delegateHandler.handleError(error);
				}
				
				public void handleCompletion() throws SQLException{
					delegateHandler.handleCompletion();
				}
			};

		// Now actually run it.

		try {
			processDeadParsedQueries();
			processDeadPortals();

			sendExecute(portal.getQuery(), portal, fetchSize);
			sendSync();

			processResults(handler, 0);
		} catch (IOException e) {
			protoConnection.close();
			handler.handleError(new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
		}

		handler.handleCompletion();
	}

	/*
	 * Receive the field descriptions from the back end.
	 */
	private Field[] receiveFields() throws IOException
	{
		int l_msgSize = pgStream.ReceiveIntegerR(4);
		int size = pgStream.ReceiveIntegerR(2);
		Field[] fields = new Field[size];

		if (Driver.logDebug)
			Driver.debug(" <=BE RowDescription(" + size + ")");

		for (int i = 0; i < fields.length; i++)
		{
			String columnLabel = pgStream.ReceiveString();
			int tableOid = pgStream.ReceiveIntegerR(4);
			short positionInTable = (short)pgStream.ReceiveIntegerR(2);
			int typeOid = pgStream.ReceiveIntegerR(4);
			int typeLength = pgStream.ReceiveIntegerR(2);
			int typeModifier = pgStream.ReceiveIntegerR(4);
			int formatType = pgStream.ReceiveIntegerR(2);
			fields[i] = new Field(columnLabel,
								  null, /* name not yet determined */
								  typeOid, typeLength, typeModifier, tableOid, positionInTable);
			fields[i].setFormat(formatType);
		}

		return fields;
	}	
	
	private void receiveAsyncNotify() throws IOException {
		int msglen = pgStream.ReceiveIntegerR(4);
		int pid = pgStream.ReceiveIntegerR(4);
		String msg = pgStream.ReceiveString();
		String param = pgStream.ReceiveString();
		protoConnection.addNotification(new org.postgresql.core.Notification(msg, pid, param));
		
		if (Driver.logDebug)
			Driver.debug(" <=BE AsyncNotify(" + pid + "," + msg + "," + param + ")");		
	}

	private SQLException receiveErrorResponse() throws IOException {
		// it's possible to get more than one error message for a query
		// see libpq comments wrt backend closing a connection
		// so, append messages to a string buffer and keep processing
		// check at the bottom to see if we need to throw an exception
		
		int elen = pgStream.ReceiveIntegerR(4);
		String totalMessage = pgStream.ReceiveString(elen-4);
		ServerErrorMessage errorMsg = new ServerErrorMessage(totalMessage);

		if (Driver.logDebug)
			Driver.debug(" <=BE ErrorMessage(" + errorMsg.toString() + ")");

		return new SQLException(errorMsg.toString(), errorMsg.getSQLState());
	}

	private SQLWarning receiveNoticeResponse() throws IOException {
		int nlen = pgStream.ReceiveIntegerR(4);
		ServerErrorMessage warnMsg = new ServerErrorMessage(pgStream.ReceiveString(nlen-4));

		if (Driver.logDebug)
			Driver.debug(" <=BE NoticeResponse(" + warnMsg.toString() + ")");

		return new PSQLWarning(warnMsg);
	}

	private String receiveCommandStatus() throws IOException {
		//TODO: better handle the msg len
		int l_len = pgStream.ReceiveIntegerR(4);
		//read l_len -5 bytes (-4 for l_len and -1 for trailing \0)
		String status = pgStream.ReceiveString(l_len - 5);
		//now read and discard the trailing \0
		pgStream.Receive(1);
					
		if (Driver.logDebug)
			Driver.debug(" <=BE CommandStatus(" + status + ")");

		return status;
	}

	private void interpretCommandStatus(String status, ResultHandler handler) {
		int update_count = 0;
		long insert_oid = 0;

		if (status.startsWith("INSERT") || status.startsWith("UPDATE") || status.startsWith("DELETE") || status.startsWith("MOVE")) {
			try {
				update_count = Integer.parseInt(status.substring(1 + status.lastIndexOf(' ')));				
				if (status.startsWith("INSERT"))
					insert_oid = Long.parseLong(status.substring(1 + status.indexOf(' '),
																 status.lastIndexOf(' ')));
			} catch (NumberFormatException nfe) {
				handler.handleError(new PSQLException(GT.tr("Unable to interpret the update count in command completion tag: {0}.", status), PSQLState.CONNECTION_FAILURE));
				return;
			}
		}

		handler.handleCommandStatus(status, update_count, insert_oid);
	}

	private void receiveRFQ() throws IOException {
		if (pgStream.ReceiveIntegerR(4) != 5)
			throw new IOException("unexpected length of ReadyForQuery message");
		
		char tStatus = (char)pgStream.ReceiveChar();
		if (Driver.logDebug)
			Driver.debug(" <=BE ReadyForQuery(" + tStatus + ")");

		// Update connection state.
		switch (tStatus) {
		case 'I':
			protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_IDLE);
			break;
		case 'T':
			protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_OPEN);
			break;
		case 'E':
			protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_FAILED);
			break;
		default:
			throw new IOException("unexpected transaction state in ReadyForQuery message: " + (int)tStatus);
		}
	}

	private final ArrayList pendingParseQueue   = new ArrayList(); // list of SimpleQuery instances
	private final ArrayList pendingBindQueue    = new ArrayList(); // list of Portal instances
	private final ArrayList pendingExecuteQueue = new ArrayList(); // list of {SimpleQuery,Portal} object arrays

	private long nextUniqueID = 1;
	private final ProtocolConnectionImpl protoConnection;
	private final PGStream pgStream;

	private final SimpleQuery beginTransactionQuery = new SimpleQuery(new String[] { "BEGIN" });;
	private final static SimpleQuery EMPTY_QUERY = new SimpleQuery(new String[] { "" });
}
