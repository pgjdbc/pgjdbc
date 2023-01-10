/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.ds.jdbc23;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.ds.common.BaseDataSource;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Simple DataSource which does not perform connection pooling.  In order to use
 * the DataSource, you must set the property databaseName. The settings for
 * serverName, portNumber, user, and password are optional.  Note: these properties
 * are declared in the superclass.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public abstract class AbstractJdbc23SimpleDataSource extends BaseDataSource implements Serializable
{
    /**
     * Gets a description of this DataSource.
     */
    public String getDescription()
    {
        return "Non-Pooling DataSource from " + Driver.getVersion();
    }

    private void writeObject(ObjectOutputStream out) throws IOException
    {
        writeBaseObject(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        readBaseObject(in);
    }
}
