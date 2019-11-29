/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent.physical;

import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.ChainedCommonStreamBuilder;

import java.sql.SQLException;

public interface ChainedPhysicalStreamBuilder extends
    ChainedCommonStreamBuilder<ChainedPhysicalStreamBuilder> {

  /**
   * Open physical replication stream.
   *
   * @return not null PGReplicationStream available for fetch wal logs in binary form
   * @throws SQLException on error
   */
  PGReplicationStream start() throws SQLException;
}
