/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ietf.jgss.ChannelBinding;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

/**
 * Implements {@link GSSContext} that wraps input packets as follows:
 * {@code messageLength (message.length + padding.length), message, padding}.
 */
public class MockGSSContext implements GSSContext {
  private final Random rnd;

  public MockGSSContext(long seed, MessageProp messageProp) {
    rnd = new Random(seed);
  }

  @Override
  public byte[] initSecContext(byte[] inputBuf, int offset, int len) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int initSecContext(InputStream inStream, OutputStream outStream) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public byte[] acceptSecContext(byte[] inToken, int offset, int len) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void acceptSecContext(InputStream inStream, OutputStream outStream) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isEstablished() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void dispose() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getWrapSizeLimit(int qop, boolean confReq, int maxTokenSize) throws GSSException {
    return 100;
  }

  private static void rangeCheck(byte[] inBuf, int offset, int len) {
    assertTrue(offset >= 0,
        () -> "offset should be greater or equal than zero"
            + ", offset=" + offset + ", len=" + len + ", inBuf.length=" + inBuf.length);
    assertTrue(offset < inBuf.length,
        () -> "offset should be less than inBuf.length"
            + ", offset=" + offset + ", len=" + len + "," + " inBuf.length=" + inBuf.length);
    assertTrue(len >= 0,
        () -> "len should be greater or equal than zero"
            + ", offset=" + offset + ", len=" + len + ", inBuf.length=" + inBuf.length);
    assertTrue(offset + len <= inBuf.length,
        () -> "offset + len should not exceed buffer length"
            + ", offset=" + offset + ", len=" + len + ", inBuf.length=" + inBuf.length);
  }

  @Override
  public byte[] wrap(byte[] inBuf, int offset, int len, MessageProp msgProp) throws GSSException {
    rangeCheck(inBuf, offset, len);
    byte[] res = new byte[4 + len + rnd.nextInt(inBuf.length)];
    ByteBuffer.wrap(res).putInt(len).put(inBuf, offset, len);
    if (GSSStreamTest.DEBUG) {
      System.out.println("wrapping offset=" + offset + ", len=" + len + " into message of length " + res.length);
    }
    return res;
  }

  @Override
  public byte[] unwrap(byte[] inBuf, int offset, int len, MessageProp msgProp) throws GSSException {
    rangeCheck(inBuf, offset, len);
    ByteBuffer bb = ByteBuffer.wrap(inBuf, offset, len);
    int msgLen = bb.getInt();
    if (GSSStreamTest.DEBUG) {
      System.out.println("unwrapping unwrapping offset=" + offset + ", len=" + len + " into message of length " + msgLen);
    }
    return Arrays.copyOfRange(inBuf, offset + 4, offset + 4 + msgLen);
  }

  @Override
  public void wrap(InputStream inStream, OutputStream outStream, MessageProp msgProp) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void unwrap(InputStream inStream, OutputStream outStream, MessageProp msgProp) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public byte[] getMIC(byte[] inMsg, int offset, int len, MessageProp msgProp) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void getMIC(InputStream inStream, OutputStream outStream, MessageProp msgProp) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void verifyMIC(byte[] inToken, int tokOffset, int tokLen, byte[] inMsg, int msgOffset,
      int msgLen, MessageProp msgProp) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void verifyMIC(InputStream tokStream, InputStream msgStream, MessageProp msgProp) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public byte[] export() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestMutualAuth(boolean state) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestReplayDet(boolean state) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestSequenceDet(boolean state) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestCredDeleg(boolean state) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestAnonymity(boolean state) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestConf(boolean state) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestInteg(boolean state) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void requestLifetime(int lifetime) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void setChannelBinding(ChannelBinding cb) throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean getCredDelegState() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean getMutualAuthState() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean getReplayDetState() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean getSequenceDetState() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean getAnonymityState() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isTransferable() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isProtReady() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean getConfState() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean getIntegState() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getLifetime() {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public GSSName getSrcName() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public GSSName getTargName() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public Oid getMech() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public GSSCredential getDelegCred() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isInitiator() throws GSSException {
    throw new UnsupportedOperationException("not implemented");
  }
}
