/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;

/**
 * Signals that a length or framing check rejected what the backend sent: a length outside the
 * range valid for its message or packet, or over a pgjdbc limit, a message body read short or
 * long, a count that does not fit its message, or a stream that is not on a message boundary.
 *
 * <p>A failure of the transport itself, such as end of stream or a socket timeout, is a plain
 * {@link IOException}. When this exception ends a connection attempt during SSL or GSS
 * negotiation, authentication, or the startup messages that follow it, the driver reports it with
 * SQLState 08P01.</p>
 */
public class ProtocolViolationException extends IOException {
  private static final long serialVersionUID = 1L;

  public ProtocolViolationException(String message) {
    super(message);
  }
}
