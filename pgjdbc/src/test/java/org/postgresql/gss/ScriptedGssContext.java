/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.test.gss.MockGSSContext;

import org.ietf.jgss.MessageProp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@link org.ietf.jgss.GSSContext} that reports itself established after a set number of
 * {@code initSecContext} calls, and records the token passed to each call. Every call returns
 * the three-byte token {@link #OUTPUT_TOKEN}.
 */
final class ScriptedGssContext extends MockGSSContext {
  static final byte[] OUTPUT_TOKEN = {1, 2, 3};

  /** A context that never becomes established. */
  static final int NEVER = Integer.MAX_VALUE;

  private final int callsToEstablish;
  final List<byte[]> inputTokens = new ArrayList<>();

  ScriptedGssContext(int callsToEstablish) {
    super(0, new MessageProp(0, true));
    this.callsToEstablish = callsToEstablish;
  }

  @Override
  public byte[] initSecContext(byte[] inputBuf, int offset, int len) {
    inputTokens.add(Arrays.copyOfRange(inputBuf, offset, offset + len));
    return OUTPUT_TOKEN.clone();
  }

  @Override
  public boolean isEstablished() {
    return inputTokens.size() >= callsToEstablish;
  }

  int initSecContextCalls() {
    return inputTokens.size();
  }
}
