/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v2;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.Writer;
import java.sql.*;

import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.GT;
import org.postgresql.copy.CopyOperation;

/**
 * QueryExecutor implementation for the V2 protocol.
 */
public class QueryExecutorImpl implements QueryExecutor {
    public QueryExecutorImpl(ProtocolConnectionImpl protoConnection, PGStream pgStream, Logger logger) {
        this.protoConnection = protoConnection;
        this.pgStream = pgStream;
        this.logger = logger;
    }

    //
    // Query parsing
    //

    public Query createSimpleQuery(String sql) {
        return new V2Query(sql, false, protoConnection);
    }

    public Query createParameterizedQuery(String sql) {
        return new V2Query(sql, true, protoConnection);
    }

    //
    // Fastpath
    //

    public ParameterList createFastpathParameters(int count) {
        return new FastpathParameterList(count);
    }

    public synchronized byte[]
    fastpathCall(int fnid, ParameterList parameters, boolean suppressBegin) throws SQLException {
        if (protoConnection.getTransactionState() == ProtocolConnection.TRANSACTION_IDLE && !suppressBegin)
        {

            if (logger.logDebug())
                logger.debug("Issuing BEGIN before fastpath call.");

            ResultHandler handler = new ResultHandler() {
                                        private boolean sawBegin = false;
                                        private SQLException sqle = null;

                                        public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
                                        }

                                        public void handleCommandStatus(String status, int updateCount, long insertOID) {
                                            if (!sawBegin)
                                            {
                                                if (!status.equals("BEGIN"))
                                                    handleError(new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                                                                                  PSQLState.PROTOCOL_VIOLATION));
                                                sawBegin = true;
                                            }
                                            else
                                            {
                                                handleError(new PSQLException(GT.tr("Unexpected command status: {0}.", status),
                                                                              PSQLState.PROTOCOL_VIOLATION));
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
                                            if (sqle == null)
                                            {
                                                sqle = error;
                                            }
                                            else
                                            {
                                                sqle.setNextException(error);
                                            }
                                        }

                                        public void handleCompletion() throws SQLException{
                                            if (sqle != null)
                                                throw sqle;
                                        }
                                    };

            try
            {
                // Create and issue a dummy query to use the existing prefix infrastructure
                V2Query query = (V2Query)createSimpleQuery("");
                SimpleParameterList params = (SimpleParameterList)query.createParameterList();
                sendQuery(query, params, "BEGIN");
                processResults(query, handler, 0, 0);
            }
            catch (IOException ioe)
            {
                throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
            }
        }

        try
        {
            sendFastpathCall(fnid, (FastpathParameterList)parameters);
            return receiveFastpathResult();
        }
        catch (IOException ioe)
        {
            throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    private void sendFastpathCall(int fnid, FastpathParameterList params) throws IOException {
        // Send call.
        int count = params.getParameterCount();

        if (logger.logDebug())
            logger.debug(" FE=> FastpathCall(fnid=" + fnid + ",paramCount=" + count + ")");

        pgStream.SendChar('F');
        pgStream.SendChar(0);
        pgStream.SendInteger4(fnid);
        pgStream.SendInteger4(count);

        for (int i = 1; i <= count; ++i)
            params.writeV2FastpathValue(i, pgStream);

        pgStream.flush();
    }

    public synchronized void processNotifies() throws SQLException {
        // Asynchronous notifies only arrive when we are not in a transaction
        if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            return;
            
        try {
            while (pgStream.hasMessagePending()) {
                int c = pgStream.ReceiveChar();
                switch (c) {
                case 'A':  // Asynchronous Notify
                    receiveAsyncNotify();
                    break;
                case 'E':  // Error Message
                    throw receiveErrorMessage();
                    // break;
                case 'N':  // Error Notification
                    protoConnection.addWarning(receiveNotification());
                    break;
                default:
                    throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);
                }
            }
        } catch (IOException ioe) {
            throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    private byte[] receiveFastpathResult() throws IOException, SQLException {
        SQLException error = null;
        boolean endQuery = false;
        byte[] result = null;

        while (!endQuery)
        {
            int c = pgStream.ReceiveChar();

            switch (c)
            {
            case 'A':  // Asynchronous Notify
                receiveAsyncNotify();
                break;

            case 'E':  // Error Message
                SQLException newError = receiveErrorMessage();
                if (error == null)
                    error = newError;
                else
                    error.setNextException(newError);
                // keep processing
                break;

            case 'N':  // Error Notification
                protoConnection.addWarning(receiveNotification());
                break;

            case 'V':    // Fastpath result
                c = pgStream.ReceiveChar();
                if (c == 'G')
                {
                    if (logger.logDebug())
                        logger.debug(" <=BE FastpathResult");

                    // Result.
                    int len = pgStream.ReceiveInteger4();
                    result = pgStream.Receive(len);
                    c = pgStream.ReceiveChar();
                }
                else
                {
                    if (logger.logDebug())
                        logger.debug(" <=BE FastpathVoidResult");
                }

                if (c != '0')
                    throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);

                break;

            case 'Z':
                if (logger.logDebug())
                    logger.debug(" <=BE ReadyForQuery");
                endQuery = true;
                break;

            default:
                throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);
            }

        }

        // did we get an error during this query?
        if (error != null)
            throw error;

        return result;
    }

    //
    // Query execution
    //

    public synchronized void execute(Query query,
                                     ParameterList parameters,
                                     ResultHandler handler,
                                     int maxRows, int fetchSize, int flags)
    throws SQLException
    {
        execute((V2Query)query, (SimpleParameterList)parameters, handler, maxRows, flags);
    }

    // Nothing special yet, just run the queries one at a time.
    public synchronized void execute(Query[] queries,
                                     ParameterList[] parameters,
                                     ResultHandler handler,
                                     int maxRows, int fetchSize, int flags)
    throws SQLException
    {
        final ResultHandler delegateHandler = handler;
        handler = new ResultHandler() {
                      public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
                          delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
                      }

                      public void handleCommandStatus(String status, int updateCount, long insertOID) {
                          delegateHandler.handleCommandStatus(status, updateCount, insertOID);
                      }

                      public void handleWarning(SQLWarning warning) {
                          delegateHandler.handleWarning(warning);
                      }

                      public void handleError(SQLException error) {
                          delegateHandler.handleError(error);
                      }

                      public void handleCompletion() throws SQLException {
                      }
                  };

        for (int i = 0; i < queries.length; ++i)
            execute((V2Query)queries[i], (SimpleParameterList)parameters[i], handler, maxRows, flags);

        delegateHandler.handleCompletion();
    }

    public void fetch(ResultCursor cursor, ResultHandler handler, int rows) throws SQLException {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "fetch(ResultCursor,ResultHandler,int)");
    }

    private void execute(V2Query query,
                         SimpleParameterList parameters,
                         ResultHandler handler,
                         int maxRows, int flags) throws SQLException
    {

        // The V2 protocol has no support for retrieving metadata
        // without executing the whole query.
        if ((flags & QueryExecutor.QUERY_DESCRIBE_ONLY) != 0)
            return;

        if (parameters == null)
            parameters = (SimpleParameterList)query.createParameterList();

        parameters.checkAllParametersSet();

        String queryPrefix = null;
        if (protoConnection.getTransactionState() == ProtocolConnection.TRANSACTION_IDLE &&
                (flags & QueryExecutor.QUERY_SUPPRESS_BEGIN) == 0)
        {

            queryPrefix = "BEGIN;";

            // Insert a handler that intercepts the BEGIN.
            final ResultHandler delegateHandler = handler;
            handler = new ResultHandler() {
                          private boolean sawBegin = false;

                          public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
                              if (sawBegin)
                                  delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
                          }

                          public void handleCommandStatus(String status, int updateCount, long insertOID) {
                              if (!sawBegin)
                              {
                                  if (!status.equals("BEGIN"))
                                      handleError(new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                                                                    PSQLState.PROTOCOL_VIOLATION));
                                  sawBegin = true;
                              }
                              else
                              {
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

        try
        {
            sendQuery(query, parameters, queryPrefix);
            processResults(query, handler, maxRows, flags);
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }

        handler.handleCompletion();
    }

    /*
     * Send a query to the backend.
     */
    protected void sendQuery(V2Query query, SimpleParameterList params, String queryPrefix) throws IOException {
        if (logger.logDebug())
            logger.debug(" FE=> Query(\"" + (queryPrefix == null ? "" : queryPrefix) + query.toString(params) + "\")");

        pgStream.SendChar('Q');

        Writer encodingWriter = pgStream.getEncodingWriter();

        if (queryPrefix != null)
            encodingWriter.write(queryPrefix);

        String[] fragments = query.getFragments();
        for (int i = 0 ; i < fragments.length; ++i)
        {
            encodingWriter.write(fragments[i]);
            if (i < params.getParameterCount())
                params.writeV2Value(i + 1, encodingWriter);
        }

        encodingWriter.write(0);
        pgStream.flush();
    }

    protected void processResults(Query originalQuery, ResultHandler handler, int maxRows, int flags) throws IOException {
        boolean bothRowsAndStatus = (flags & QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS) != 0;
        Field[] fields = null;
        List tuples = null;

        boolean endQuery = false;
        while (!endQuery)
        {
            int c = pgStream.ReceiveChar();

            switch (c)
            {
            case 'A':  // Asynchronous Notify
                receiveAsyncNotify();
                break;

            case 'B':  // Binary Data Transfer
                {
                    if (fields == null)
                        throw new IOException("Data transfer before field metadata");

                    if (logger.logDebug())
                        logger.debug(" <=BE BinaryRow");

                    Object tuple = null;
                    try {
                        tuple = pgStream.ReceiveTupleV2(fields.length, true);
                    } catch(OutOfMemoryError oome) {
                        if (maxRows == 0 || tuples.size() < maxRows) {
                            handler.handleError(new PSQLException(GT.tr("Ran out of memory retrieving query results."), PSQLState.OUT_OF_MEMORY, oome));
                        }
                    }

                    for (int i = 0; i < fields.length; i++)
                        fields[i].setFormat(Field.BINARY_FORMAT); //Set the field to binary format
                    if (maxRows == 0 || tuples.size() < maxRows)
                        tuples.add(tuple);
                }
                break;

            case 'C':  // Command Status
                String status = pgStream.ReceiveString();

                if (logger.logDebug())
                    logger.debug(" <=BE CommandStatus(" + status + ")");

                if (fields != null)
                {
                    handler.handleResultRows(originalQuery, fields, tuples, null);
                    fields = null;

                    if (bothRowsAndStatus)
                        interpretCommandStatus(status, handler);
                }
                else
                {
                    interpretCommandStatus(status, handler);
                }

                break;

            case 'D':  // Text Data Transfer
                {
                    if (fields == null)
                        throw new IOException("Data transfer before field metadata");

                    if (logger.logDebug())
                        logger.debug(" <=BE DataRow");

                    Object tuple = null;
                    try {
                        tuple = pgStream.ReceiveTupleV2(fields.length, false);
                    } catch(OutOfMemoryError oome) {
                        if (maxRows == 0 || tuples.size() < maxRows)
                            handler.handleError(new PSQLException(GT.tr("Ran out of memory retrieving query results."), PSQLState.OUT_OF_MEMORY, oome));
                    }
                    if (maxRows == 0 || tuples.size() < maxRows)
                        tuples.add(tuple);
                }

                break;

            case 'E':  // Error Message
                handler.handleError(receiveErrorMessage());
                // keep processing
                break;

            case 'I':  // Empty Query
                if (logger.logDebug())
                    logger.debug(" <=BE EmptyQuery");
                c = pgStream.ReceiveChar();
                if (c != 0)
                    throw new IOException("Expected \\0 after EmptyQuery, got: " + c);
                break;

            case 'N':  // Error Notification
                handler.handleWarning(receiveNotification());
                break;

            case 'P':  // Portal Name
                String portalName = pgStream.ReceiveString();
                if (logger.logDebug())
                    logger.debug(" <=BE PortalName(" + portalName + ")");
                break;

            case 'T':  // MetaData Field Description
                fields = receiveFields();
                tuples = new ArrayList();
                break;

            case 'Z':
                if (logger.logDebug())
                    logger.debug(" <=BE ReadyForQuery");
                endQuery = true;
                break;

            default:
                throw new IOException("Unexpected packet type: " + c);
            }

        }
    }

    /*
     * Receive the field descriptions from the back end.
     */
    private Field[] receiveFields() throws IOException
    {
        int size = pgStream.ReceiveInteger2();
        Field[] fields = new Field[size];

        if (logger.logDebug())
            logger.debug(" <=BE RowDescription(" + fields.length + ")");

        for (int i = 0; i < fields.length; i++)
        {
            String columnLabel = pgStream.ReceiveString();
            int typeOid = pgStream.ReceiveInteger4();
            int typeLength = pgStream.ReceiveInteger2();
            int typeModifier = pgStream.ReceiveInteger4();
            fields[i] = new Field(columnLabel, columnLabel, typeOid, typeLength, typeModifier, 0, 0);
        }

        return fields;
    }

    private void receiveAsyncNotify() throws IOException {
        int pid = pgStream.ReceiveInteger4();
        String msg = pgStream.ReceiveString();

        if (logger.logDebug())
            logger.debug(" <=BE AsyncNotify(pid=" + pid + ",msg=" + msg + ")");

        protoConnection.addNotification(new org.postgresql.core.Notification(msg, pid));
    }

    private SQLException receiveErrorMessage() throws IOException {
        String errorMsg = pgStream.ReceiveString().trim();
        if (logger.logDebug())
            logger.debug(" <=BE ErrorResponse(" + errorMsg + ")");
        return new PSQLException(errorMsg, PSQLState.UNKNOWN_STATE);
    }

    private SQLWarning receiveNotification() throws IOException {
        String warnMsg = pgStream.ReceiveString();

        // Strip out the severity field so we have consistency with
        // the V3 protocol.  SQLWarning.getMessage should return just
        // the actual message.
        //
        int severityMark = warnMsg.indexOf(":");
        warnMsg = warnMsg.substring(severityMark+1).trim();
        if (logger.logDebug())
            logger.debug(" <=BE NoticeResponse(" + warnMsg + ")");
        return new SQLWarning(warnMsg);
    }

    private void interpretCommandStatus(String status, ResultHandler handler) throws IOException {
        int update_count = 0;
        long insert_oid = 0;

        if (status.equals("BEGIN"))
            protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_OPEN);
        else if (status.equals("COMMIT") || status.equals("ROLLBACK"))
            protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_IDLE);
        else if (status.startsWith("INSERT") || status.startsWith("UPDATE") || status.startsWith("DELETE") || status.startsWith("MOVE"))
        {
            try
            {
                update_count = Integer.parseInt(status.substring(1 + status.lastIndexOf(' ')));
                if (status.startsWith("INSERT"))
                    insert_oid = Long.parseLong(status.substring(1 + status.indexOf(' '),
                                                status.lastIndexOf(' ')));
            }
            catch (NumberFormatException nfe)
            {
                handler.handleError(new PSQLException(GT.tr("Unable to interpret the update count in command completion tag: {0}.", status), PSQLState.CONNECTION_FAILURE));
                return ;
            }
        }

        handler.handleCommandStatus(status, update_count, insert_oid);
    }

    private final ProtocolConnectionImpl protoConnection;
    private final PGStream pgStream;
    private final Logger logger;

    public CopyOperation startCopy(String sql, boolean suppressBegin) throws SQLException {
        throw new PSQLException(GT.tr("Copy not implemented for protocol version 2"), PSQLState.NOT_IMPLEMENTED);
    }
}
