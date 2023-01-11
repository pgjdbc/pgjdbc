/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core.v3;

import java.sql.SQLException;

import legacy.org.postgresql.copy.CopyOut;

/**
 * Anticipated flow of a COPY TO STDOUT operation:
 * 
 * CopyManager.copyOut()
 *   ->QueryExecutor.startCopy()
 *       - sends given query to server
 *       ->processCopyResults():
 *           - receives CopyOutResponse from Server
 *           - creates new CopyOutImpl
 *           ->initCopy():
 *              - receives copy metadata from server
 *              ->CopyOutImpl.init()
 *              ->lock() connection for this operation
 *   - if query fails an exception is thrown
 *   - if query returns wrong CopyOperation, copyOut() cancels it before throwing exception
 * <-returned: new CopyOutImpl holding lock on connection
 * repeat CopyOut.readFromCopy() until null
 *   ->CopyOutImpl.readFromCopy()
 *       ->QueryExecutorImpl.readFromCopy()
 *           ->processCopyResults()
 *               - on copydata row from server
 *                   ->CopyOutImpl.handleCopydata() stores reference to byte array
 *               -  on CopyDone, CommandComplete, ReadyForQuery
 *                   ->unlock() connection for use by other operations
 * <-returned: byte array of data received from server or null at end.
 */
public class CopyOutImpl extends CopyOperationImpl implements CopyOut {
    private byte[] currentDataRow;

    public byte[] readFromCopy() throws SQLException {
        currentDataRow = null;
        queryExecutor.readFromCopy(this);
        return currentDataRow;
    }

    void handleCopydata(byte[] data) {
        currentDataRow = data;
    }
}
