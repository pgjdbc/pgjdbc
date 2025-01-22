/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import org.postgresql.gss.GSSOutputStream;
import org.postgresql.util.NullOutputStream;
import org.postgresql.util.internal.PgBufferedOutputStream;

import org.ietf.jgss.ChannelBinding;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;
import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;

public class GSSOutputStreamTest {
  private final MessageProp messageProp = new MessageProp(0, true);
  private final GSSContext gssContext = new FakeGSSContext();
  private final PgBufferedOutputStream nullOutputStream =
      new PgBufferedOutputStream(new NullOutputStream(), 10);

  @Test
  public void testGSSMessageBuffer() throws Exception {
    GSSOutputStream gssOutputStream = new GSSOutputStream(nullOutputStream, gssContext,
        messageProp, 10);

    gssOutputStream.write(new byte[0]);
    gssOutputStream.write(new byte[1]);
    gssOutputStream.write(new byte[9]);
    gssOutputStream.write(new byte[1]);
    gssOutputStream.write(new byte[100]);
  }

  public static class FakeGSSContext implements GSSContext {
    @Override
    public byte[] initSecContext(byte[] bytes, int i, int i1) throws GSSException {
      return bytes;
    }

    @Override
    public int initSecContext(InputStream inputStream, OutputStream outputStream) throws GSSException {
      return 0;
    }

    @Override
    public byte[] acceptSecContext(byte[] bytes, int i, int i1) throws GSSException {
      return bytes;
    }

    @Override
    public void acceptSecContext(InputStream inputStream, OutputStream outputStream) throws GSSException {

    }

    @Override
    public boolean isEstablished() {
      return true;
    }

    @Override
    public void dispose() throws GSSException {

    }

    @Override
    public int getWrapSizeLimit(int i, boolean b, int maxTokenSize) throws GSSException {
      return maxTokenSize;
    }

    @Override
    public byte[] wrap(byte[] bytes, int i, int i1, MessageProp messageProp) throws GSSException {
      Assert.assertTrue("ArrayIndexOutOfBoundsException", bytes.length >= i + i1);
      return bytes;
    }

    @Override
    public void wrap(InputStream inputStream, OutputStream outputStream, MessageProp messageProp) throws GSSException {

    }

    @Override
    public byte[] unwrap(byte[] bytes, int i, int i1, MessageProp messageProp) throws GSSException {
      Assert.assertTrue("ArrayIndexOutOfBoundsException", bytes.length >= i + i1);
      return bytes;
    }

    @Override
    public void unwrap(InputStream inputStream, OutputStream outputStream, MessageProp messageProp) throws GSSException {

    }

    @Override
    public byte[] getMIC(byte[] bytes, int i, int i1, MessageProp messageProp) throws GSSException {
      return bytes;
    }

    @Override
    public void getMIC(InputStream inputStream, OutputStream outputStream, MessageProp messageProp) throws GSSException {

    }

    @Override
    public void verifyMIC(byte[] bytes, int i, int i1, byte[] bytes1, int i2, int i3, MessageProp messageProp) throws GSSException {

    }

    @Override
    public void verifyMIC(InputStream inputStream, InputStream inputStream1, MessageProp messageProp) throws GSSException {

    }

    @Override
    public byte[] export() throws GSSException {
      return null;
    }

    @Override
    public void requestMutualAuth(boolean b) throws GSSException {

    }

    @Override
    public void requestReplayDet(boolean b) throws GSSException {

    }

    @Override
    public void requestSequenceDet(boolean b) throws GSSException {

    }

    @Override
    public void requestCredDeleg(boolean b) throws GSSException {

    }

    @Override
    public void requestAnonymity(boolean b) throws GSSException {

    }

    @Override
    public void requestConf(boolean b) throws GSSException {

    }

    @Override
    public void requestInteg(boolean b) throws GSSException {

    }

    @Override
    public void requestLifetime(int i) throws GSSException {

    }

    @Override
    public void setChannelBinding(ChannelBinding channelBinding) throws GSSException {

    }

    @Override
    public boolean getCredDelegState() {
      return false;
    }

    @Override
    public boolean getMutualAuthState() {
      return false;
    }

    @Override
    public boolean getReplayDetState() {
      return false;
    }

    @Override
    public boolean getSequenceDetState() {
      return false;
    }

    @Override
    public boolean getAnonymityState() {
      return false;
    }

    @Override
    public boolean isTransferable() throws GSSException {
      return false;
    }

    @Override
    public boolean isProtReady() {
      return false;
    }

    @Override
    public boolean getConfState() {
      return false;
    }

    @Override
    public boolean getIntegState() {
      return false;
    }

    @Override
    public int getLifetime() {
      return 0;
    }

    @Override
    public GSSName getSrcName() throws GSSException {
      return null;
    }

    @Override
    public GSSName getTargName() throws GSSException {
      return null;
    }

    @Override
    public Oid getMech() throws GSSException {
      return null;
    }

    @Override
    public GSSCredential getDelegCred() throws GSSException {
      return null;
    }

    @Override
    public boolean isInitiator() throws GSSException {
      return false;
    }
  }
}
