/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent;

import org.postgresql.replication.ReplicationSlotInfo;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Fluent interface for specify common parameters for create Logical and Physical replication slot.
 */
public interface ChainedCommonCreateSlotBuilder<T extends ChainedCommonCreateSlotBuilder<T>> {

  /**
   * Replication slots provide an automated way to ensure that the master does not remove WAL
   * segments until they have been received by all standbys, and that the master does not remove
   * rows which could cause a recovery conflict even when the standby is disconnected.
   *
   * @param slotName not null unique replication slot name for create.
   * @return T a slot builder
   */
  T withSlotName(String slotName);

  /**
   * <p>Temporary slots are not saved to disk and are automatically dropped on error or when
   * the session has finished.</p>
   *
   * <p>This feature is only supported by PostgreSQL versions &gt;= 10.</p>
   *
   * @return T a slot builder
   * @throws SQLFeatureNotSupportedException thrown if PostgreSQL version is less than 10.
   */
  T withTemporaryOption() throws SQLFeatureNotSupportedException;

  /**
   * Create slot with specified parameters in database.
   *
   * @return ReplicationSlotInfo with the information of the created slot.
   * @throws SQLException on error
   */
  ReplicationSlotInfo make() throws SQLException;
}
