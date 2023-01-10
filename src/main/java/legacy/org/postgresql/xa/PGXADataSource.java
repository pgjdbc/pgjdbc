package legacy.org.postgresql.xa;

import legacy.org.postgresql.xa.jdbc4.AbstractJdbc4XADataSource;

import javax.sql.XADataSource;

/**
 * XA-enabled DataSource implementation.
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 */
public class PGXADataSource
    extends AbstractJdbc4XADataSource
    implements XADataSource
{
}

