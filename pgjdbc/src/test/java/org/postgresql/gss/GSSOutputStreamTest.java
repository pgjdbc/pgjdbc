/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.util.internal.PgBufferedOutputStream;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The context is a stub that records the privacy requested from each wrap call, so the streams are
 * exercised without a Kerberos realm.
 */
class GSSOutputStreamTest {
  private static GSSContext recordingContext(List<Boolean> requestedPrivacy, boolean canSeal) {
    return (GSSContext) Proxy.newProxyInstance(GSSOutputStreamTest.class.getClassLoader(),
        new Class<?>[]{GSSContext.class}, (proxy, method, args) -> {
          switch (method.getName()) {
            case "getWrapSizeLimit":
              return 1000;
            case "wrap":
              MessageProp prop = (MessageProp) args[3];
              requestedPrivacy.add(prop.getPrivacy());
              if (!canSeal) {
                prop.setPrivacy(false);
              }
              byte[] buf = (byte[]) args[0];
              int off = (Integer) args[1];
              int len = (Integer) args[2];
              return Arrays.copyOfRange(buf, off, off + len);
            default:
              return null;
          }
        });
  }

  @Test
  void privacyIsRequestedAfterTheSharedMessagePropChanges() throws Exception {
    List<Boolean> requestedPrivacy = new ArrayList<>();
    MessageProp messageProp = new MessageProp(0, true);
    GSSOutputStream out = new GSSOutputStream(
        new PgBufferedOutputStream(new ByteArrayOutputStream(), 8192),
        recordingContext(requestedPrivacy, true), messageProp, 16380);
    out.write(new byte[]{1, 2, 3});
    out.flush();
    // unwrap writes the flags of an incoming token into the MessageProp it is given
    messageProp.setPrivacy(false);
    out.write(new byte[]{4, 5, 6});
    out.flush();
    assertEquals(Arrays.asList(true, true), requestedPrivacy);
  }

  @Test
  void writeFailsWhenTheContextDoesNotSeal() throws Exception {
    List<Boolean> requestedPrivacy = new ArrayList<>();
    ByteArrayOutputStream sent = new ByteArrayOutputStream();
    PgBufferedOutputStream pgOut = new PgBufferedOutputStream(sent, 8192);
    GSSOutputStream out = new GSSOutputStream(pgOut, recordingContext(requestedPrivacy, false),
        new MessageProp(0, true), 16380);
    out.write(new byte[]{1, 2, 3});
    assertThrows(IOException.class, out::flush);
    pgOut.flush();
    assertEquals(0, sent.size(), "nothing must be sent without confidentiality");
  }
}
