/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core.v3;

import java.sql.SQLException;

import legacy.org.postgresql.copy.CopyIn;

/**
 * Anticipated flow of a COPY FROM STDIN operation:
 * 
 * CopyManager.copyIn()
 *   ->QueryExecutor.startCopy()
 *       - sends given query to server
 *       ->processCopyResults():
 *           - receives CopyInResponse from Server
 *           - creates new CopyInImpl
 *           ->initCopy():
 *              - receives copy metadata from server
 *              ->CopyInImpl.init()
 *              ->lock() connection for this operation
 *   - if query fails an exception is thrown
 *   - if query returns wrong CopyOperation, copyIn() cancels it before throwing exception
 * <-return: new CopyInImpl holding lock on connection
 * repeat CopyIn.writeToCopy() for all data
 *   ->CopyInImpl.writeToCopy()
 *       ->QueryExecutorImpl.writeToCopy()
 *           - sends given data
 *           ->processCopyResults()
 *               - parameterized not to block, just peek for new messages from server
 *               - on ErrorResponse, waits until protocol is restored and unlocks connection
 * CopyIn.endCopy()
 *   ->CopyInImpl.endCopy()
 *       ->QueryExecutorImpl.endCopy()
 *           - sends CopyDone
 *           - processCopyResults()
 *               - on CommandComplete
 *                   ->CopyOperationImpl.handleCommandComplete()
 *                     - sets updatedRowCount when applicable
 *               - on ReadyForQuery unlock() connection for use by other operations 
 * <-return: CopyInImpl.getUpdatedRowCount()
 */
public class CopyInImpl extends CopyOperationImpl implements CopyIn {

    public void writeToCopy(byte[] data, int off, int siz) throws SQLException {
        queryExecutor.writeToCopy(this, data, off, siz);
    }

    public void flushCopy() throws SQLException {
        queryExecutor.flushCopy(this);
    }

    public long endCopy() throws SQLException {
        return queryExecutor.endCopy(this);
    }
}
