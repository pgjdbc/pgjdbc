/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent.logical;

import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.fluent.ChainedCommonStreamBuilder;

import java.sql.SQLException;
import java.util.Properties;

public interface ChainedLogicalStreamBuilder
    extends ChainedCommonStreamBuilder<ChainedLogicalStreamBuilder> {
  /**
   * Open logical replication stream
   *
   * @return not null PGReplicationStream available for fetch data in logical form
   * @throws SQLException
   */
  PGReplicationStream start() throws SQLException;

  /**
   *
   * @param optionName
   * @param optionValue
   * @return
   */

  ChainedLogicalStreamBuilder withSlotOption(String optionName, boolean optionValue);

  /**
   *
   * @param optionName
   * @param optionValue
   * @return
   */
  ChainedLogicalStreamBuilder withSlotOption(String optionName, int optionValue);

  /**
   *
   * @param optionName
   * @param optionValue
   * @return
   */
  ChainedLogicalStreamBuilder withSlotOption(String optionName, String optionValue);

  /**
   *
   * @param options
   * @return
   */
  ChainedLogicalStreamBuilder withSlotOptions(Properties options);

}
