/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.replication;

import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;

/**
 * Shortens a connection's socket timeout for as long as a replication stream runs, and holds the
 * settings the stream has to put back when it ends.
 *
 * <p>A stream reads with a socket timeout of one status interval so that a blocking read wakes up
 * often enough to send a standby status update. That timeout belongs to the stream rather than to
 * the connection: every later operation on the connection — including the
 * {@code START_REPLICATION} of a second stream — has to see the connection's own timeout again.
 *
 * <p>Both directions set the timeout on the socket rather than through
 * {@link PGStream#setNetworkTimeout(int)}, so the driver keeps reporting timeouts the way the
 * connection was opened to, whatever the status interval is.
 */
final class ReplicationSocketSettings {
  private final PGStream pgStream;
  private final int soTimeout;
  private final int streamAvailableCheckDelay;

  private ReplicationSocketSettings(PGStream pgStream, int soTimeout,
      int streamAvailableCheckDelay) {
    this.pgStream = pgStream;
    this.soTimeout = soTimeout;
    this.streamAvailableCheckDelay = streamAvailableCheckDelay;
  }

  /**
   * Shortens the socket timeout to the status interval, keeping the shorter of the two when the
   * connection already asked for one. Call this once {@code START_REPLICATION} has been answered:
   * the handshake is a single exchange and has no reason to run under a status interval.
   *
   * @param pgStream       the connection the replication stream runs on
   * @param statusInterval milliseconds between standby status updates; zero disables the periodic
   *                       updates, and then the socket keeps the timeout it has
   * @return the settings the connection had, for {@link #restore()} to put back
   * @throws PSQLException if the socket does not accept the timeout
   */
  static ReplicationSocketSettings shorten(PGStream pgStream, int statusInterval)
      throws PSQLException {
    try {
      ReplicationSocketSettings previous = new ReplicationSocketSettings(pgStream,
          pgStream.getSocket().getSoTimeout(), pgStream.getMinStreamAvailableCheckDelay());
      if (statusInterval != 0) {
        pgStream.getSocket().setSoTimeout(previous.soTimeout > 0
            ? Math.min(previous.soTimeout, statusInterval)
            : statusInterval);
        // Use blocking 1ms reads for `available()` checks
        pgStream.setMinStreamAvailableCheckDelay(0);
      }
      return previous;
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("An error occurred while trying to get the socket timeout."),
          PSQLState.CONNECTION_FAILURE, ioe);
    }
  }

  /**
   * Puts the captured settings back, so the connection reads with its own socket timeout again.
   * Does nothing once the socket is closed, since nothing will read from it.
   *
   * @throws PSQLException if the socket does not accept the timeout
   */
  void restore() throws PSQLException {
    if (pgStream.isClosed()) {
      return;
    }
    try {
      pgStream.getSocket().setSoTimeout(soTimeout);
      pgStream.setMinStreamAvailableCheckDelay(streamAvailableCheckDelay);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("An error occurred while trying to reset the socket timeout."),
          PSQLState.CONNECTION_FAILURE, ioe);
    }
  }
}
