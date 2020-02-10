/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent;

import org.postgresql.replication.fluent.logical.ChainedLogicalCreateSlotBuilder;
import org.postgresql.replication.fluent.physical.ChainedPhysicalCreateSlotBuilder;

/**
 * Fluent interface for specify common parameters for Logical and Physical replication.
 */
public interface ChainedCreateReplicationSlotBuilder {
  /**
   * Get the logical slot builder.
   * Example usage:
   * <pre>
   *   {@code
   *
   *    pgConnection
   *        .getReplicationAPI()
   *        .createReplicationSlot()
   *        .logical()
   *        .withSlotName("mySlot")
   *        .withOutputPlugin("test_decoding")
   *        .make();
   *
   *    PGReplicationStream stream =
   *        pgConnection
   *            .getReplicationAPI()
   *            .replicationStream()
   *            .logical()
   *            .withSlotName("mySlot")
   *            .withSlotOption("include-xids", false)
   *            .withSlotOption("skip-empty-xacts", true)
   *            .start();
   *
   *    while (true) {
   *      ByteBuffer buffer = stream.read();
   *      //process logical changes
   *    }
   *
   *   }
   * </pre>
   * @return not null fluent api
   */
  ChainedLogicalCreateSlotBuilder logical();

  /**
   * <p>Create physical replication stream for process wal logs in binary form.</p>
   *
   * <p>Example usage:</p>
   * <pre>
   *   {@code
   *
   *    pgConnection
   *        .getReplicationAPI()
   *        .createReplicationSlot()
   *        .physical()
   *        .withSlotName("mySlot")
   *        .make();
   *
   *    PGReplicationStream stream =
   *        pgConnection
   *            .getReplicationAPI()
   *            .replicationStream()
   *            .physical()
   *            .withSlotName("mySlot")
   *            .start();
   *
   *    while (true) {
   *      ByteBuffer buffer = stream.read();
   *      //process binary WAL logs
   *    }
   *
   *   }
   * </pre>
   *
   * @return not null fluent api
   */
  ChainedPhysicalCreateSlotBuilder physical();
}
