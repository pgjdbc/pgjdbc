/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication.fluent;

import org.postgresql.replication.fluent.logical.ChainedLogicalStreamBuilder;
import org.postgresql.replication.fluent.physical.ChainedPhysicalStreamBuilder;

/**
 * Start point for fluent API that build replication stream(logical or physical).
 * Api not thread safe, and can be use only for crate single stream.
 */
public interface ChainedStreamBuilder {
  /**
   * <p>Create logical replication stream that decode raw wal logs by output plugin to logical form.
   * Default about logical decoding you can see by following link
   * <a href="http://www.postgresql.org/docs/current/static/logicaldecoding-explanation.html">
   *     Logical Decoding Concepts
   * </a>.
   * </p>
   *
   * <p>Example usage:</p>
   * <pre>
   *   {@code
   *
   *    PGReplicationStream stream =
   *        pgConnection
   *            .getReplicationAPI()
   *            .replicationStream()
   *            .logical()
   *            .withSlotName("test_decoding")
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
   *
   * @return not null fluent api
   */
  ChainedLogicalStreamBuilder logical();

  /**
   * <p>Create physical replication stream for process wal logs in binary form.</p>
   *
   * <p>Example usage:</p>
   * <pre>
   *   {@code
   *
   *    LogSequenceNumber lsn = getCurrentLSN();
   *
   *    PGReplicationStream stream =
   *        pgConnection
   *            .getReplicationAPI()
   *            .replicationStream()
   *            .physical()
   *            .withStartPosition(lsn)
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
  ChainedPhysicalStreamBuilder physical();
}
