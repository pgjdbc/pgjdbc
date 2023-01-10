/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package legacy.org.postgresql.jdbc3g;

import legacy.org.postgresql.core.BaseStatement;
import legacy.org.postgresql.core.Field;
import legacy.org.postgresql.core.Query;
import legacy.org.postgresql.core.ResultCursor;
import legacy.org.postgresql.jdbc3.AbstractJdbc3ResultSet;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.util.UUID;
import java.util.Vector;

public abstract class AbstractJdbc3gResultSet extends AbstractJdbc3ResultSet
{

    public AbstractJdbc3gResultSet(Query originalQuery, BaseStatement statement, Field[] fields, Vector tuples,
                                   ResultCursor cursor, int maxRows, int maxFieldSize, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super (originalQuery, statement, fields, tuples, cursor, maxRows, maxFieldSize, rsType, rsConcurrency, rsHoldability);
    }


    protected Object getUUID(String data) throws SQLException
    {
        UUID uuid;
        try {
            uuid = UUID.fromString(data);
        } catch (java.lang.IllegalArgumentException iae) {
            throw new PSQLException(GT.tr("Invalid UUID data."), PSQLState.INVALID_PARAMETER_VALUE, iae);
        }

        return uuid;
    }
}

