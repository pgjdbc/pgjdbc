/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.replication;

import org.postgresql.core.PGStream;

/**
 * Gives a replication stream a wait of its own for as long as it runs, and holds the settings the
 * stream has to put back when it ends.
 *
 * <p>A stream has to stop waiting once per status interval so that a blocking read can send a
 * standby status update. That wait is a {@linkplain PGStream#setReadWakeupTimeout(int) read
 * wake-up} rather than the connection's socket timeout, so it covers only the byte that starts a
 * message: there the driver has consumed nothing yet and giving up costs nothing, while partway
 * through a message it would drop the bytes already taken and desynchronize the stream.
 *
 * <p>The connection's own timeout is left alone, so it stays in charge of the
 * {@code START_REPLICATION} handshake, of the body of every message, and of every operation that
 * outlives the stream.
 */
final class ReplicationSocketSettings {
  private final PGStream pgStream;
  private final int readWakeupTimeout;
  private final int streamAvailableCheckDelay;

  private ReplicationSocketSettings(PGStream pgStream, int readWakeupTimeout,
      int streamAvailableCheckDelay) {
    this.pgStream = pgStream;
    this.readWakeupTimeout = readWakeupTimeout;
    this.streamAvailableCheckDelay = streamAvailableCheckDelay;
  }

  /**
   * Gives the connection a read wake-up of one status interval.
   *
   * @param pgStream       the connection the replication stream runs on
   * @param statusInterval milliseconds between standby status updates; zero disables the periodic
   *                       updates, and then the connection keeps the settings it has
   * @return the settings the connection had, for {@link #restore()} to put back
   */
  static ReplicationSocketSettings shorten(PGStream pgStream, int statusInterval) {
    ReplicationSocketSettings previous = new ReplicationSocketSettings(pgStream,
        pgStream.getReadWakeupTimeout(), pgStream.getMinStreamAvailableCheckDelay());
    if (statusInterval != 0) {
      pgStream.setReadWakeupTimeout(statusInterval);
      // Use blocking 1ms reads for `available()` checks
      pgStream.setMinStreamAvailableCheckDelay(0);
    }
    return previous;
  }

  /** Puts the captured settings back, so the connection reads the way it did before the stream. */
  void restore() {
    pgStream.setReadWakeupTimeout(readWakeupTimeout);
    pgStream.setMinStreamAvailableCheckDelay(streamAvailableCheckDelay);
  }
}
