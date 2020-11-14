/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Information returned on replication slot creation.
 *
 * <p>Returned keys of CREATE_REPLICATION_SLOT:
 * <ol>
 * <li><b>slot_name</b> String {@code =>} the slot name
 * <li><b>consistent_point</b> String {@code =>} LSN at which we became consistent
 * <li><b>snapshot_name</b> String {@code =>} exported snapshot's name (may be <code>null</code>)
 * <li><b>output_plugin</b> String {@code =>} output plugin (may be <code>null</code>)
 * </ol>
 *
 * @see <a href="https://www.postgresql.org/docs/12/protocol-replication.html#PROTOCOL-REPLICATION-CREATE-SLOT">CREATE_REPLICATION_SLOT documentation</a>
 */
public final class ReplicationSlotInfo {

  private final String slotName;
  private final ReplicationType replicationType;
  private final LogSequenceNumber consistentPoint;
  private final @Nullable String snapshotName;
  private final @Nullable String outputPlugin;

  public ReplicationSlotInfo(String slotName, ReplicationType replicationType,
      LogSequenceNumber consistentPoint, @Nullable String snapshotName,
      @Nullable String outputPlugin) {
    this.slotName = slotName;
    this.replicationType = replicationType;
    this.consistentPoint = consistentPoint;
    this.snapshotName = snapshotName;
    this.outputPlugin = outputPlugin;
  }

  /**
   * Replication slot name.
   *
   * @return the slot name
   */
  public String getSlotName() {
    return slotName;
  }

  /**
   * Replication type of the slot created, might be PHYSICAL or LOGICAL.
   *
   * @return ReplicationType, PHYSICAL or LOGICAL
   */
  public ReplicationType getReplicationType() {
    return replicationType;
  }

  /**
   * LSN at which we became consistent.
   *
   * @return LogSequenceNumber with the consistent_point
   */
  public LogSequenceNumber getConsistentPoint() {
    return consistentPoint;
  }

  /**
   * Exported snapshot name at the point of replication slot creation.
   *
   * <p>As long as the exporting transaction remains open, other transactions can import its snapshot,
   * and thereby be guaranteed that they see exactly the same view of the database that the first
   * transaction sees.
   *
   * @return exported snapshot_name (may be <code>null</code>)
   */
  public @Nullable String getSnapshotName() {
    return snapshotName;
  }

  /**
   * Output Plugin used on slot creation.
   *
   * @return output_plugin (may be <code>null</code>)
   */
  public @Nullable String getOutputPlugin() {
    return outputPlugin;
  }

}
