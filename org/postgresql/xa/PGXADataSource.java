package org.postgresql.xa;

import javax.sql.XADataSource;

/**
 * XA-enabled DataSource implementation.
 *
 * @author Heikki Linnakangas (heikki.linnakangas@iki.fi)
 */
public class PGXADataSource
    extends AbstractPGXADataSource
    implements XADataSource
{
}

