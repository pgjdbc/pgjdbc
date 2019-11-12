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
   * Open logical replication stream.
   *
   * @return not null PGReplicationStream available for fetch data in logical form
   * @throws SQLException  if there are errors
   */
  PGReplicationStream start() throws SQLException;

  /**
   *
   * @param optionName name of option
   * @param optionValue boolean value
   * @return ChainedLogicalStreamBuilder
   */

  ChainedLogicalStreamBuilder withSlotOption(String optionName, boolean optionValue);

  /**
   *
   * @param optionName name of option
   * @param optionValue integer value
   * @return ChainedLogicalStreamBuilder
   */
  ChainedLogicalStreamBuilder withSlotOption(String optionName, int optionValue);

  /**
   *
   * @param optionName name of option
   * @param optionValue String value
   * @return ChainedLogicalStreamBuilder
   */
  ChainedLogicalStreamBuilder withSlotOption(String optionName, String optionValue);

  /**
   *
   * @param options properties
   * @return ChainedLogicalStreamBuilder
   */
  ChainedLogicalStreamBuilder withSlotOptions(Properties options);

}
