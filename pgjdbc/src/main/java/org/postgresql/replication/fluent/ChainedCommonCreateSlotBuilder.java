/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent;

import java.sql.SQLException;

/**
 * Fluent interface for specify common parameters for create Logical and Physical replication slot.
 */
public interface ChainedCommonCreateSlotBuilder<T extends ChainedCommonCreateSlotBuilder<T>>  {

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
   * Create slot with specified parameters in database.
   * @throws SQLException on error
   */
  void make() throws SQLException;
}
