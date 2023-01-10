package legacy.org.postgresql.ds.jdbc4;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.ds.jdbc23.AbstractJdbc23ConnectionPoolDataSource;

public class AbstractJdbc4ConnectionPoolDataSource
	extends AbstractJdbc23ConnectionPoolDataSource
{

    public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException
    {
        throw Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

}

