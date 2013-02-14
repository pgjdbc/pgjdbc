package org.postgresql.xa;

import javax.sql.XADataSource;

/**
 * XA-enabled DataSource implementation.
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 */
public abstract class AbstractPGXADataSource
    extends org.postgresql.xa.jdbc4.AbstractJdbc4XADataSource
    implements XADataSource
{
}

