/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;

class GSSInputStreamTest {
  private static byte[] packet(int declaredLength, int payloadBytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(declaredLength >>> 24);
    out.write(declaredLength >>> 16);
    out.write(declaredLength >>> 8);
    out.write(declaredLength);
    for (int i = 0; i < payloadBytes; i++) {
      out.write('x');
    }
    return out.toByteArray();
  }

  /** A context whose unwrap returns its input and reports the given token state. */
  private static GSSContext reportingContext(String state) {
    return (GSSContext) Proxy.newProxyInstance(GSSInputStreamTest.class.getClassLoader(),
        new Class<?>[]{GSSContext.class}, (proxy, method, args) -> {
          if ("unwrap".equals(method.getName())) {
            MessageProp prop = (MessageProp) args[3];
            prop.setSupplementaryStates("duplicate".equals(state), "old".equals(state),
                "unseq".equals(state), "gap".equals(state), 0, null);
            prop.setPrivacy(!"unsealed".equals(state));
            byte[] buf = (byte[]) args[0];
            int off = (Integer) args[1];
            int len = (Integer) args[2];
            return Arrays.copyOfRange(buf, off, off + len);
          }
          return null;
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate", "old", "unseq", "gap", "unsealed"})
  void refusesATokenThatIsReplayedOutOfSequenceOrUnsealed(String state) {
    GSSInputStream in = new GSSInputStream(new ByteArrayInputStream(packet(16, 16)),
        reportingContext(state), new MessageProp(0, true));

    assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));
  }

  @Test
  void acceptsAnInSequenceSealedToken() throws IOException {
    GSSInputStream in = new GSSInputStream(new ByteArrayInputStream(packet(16, 16)),
        reportingContext("ok"), new MessageProp(0, true));

    assertEquals(16, in.read(new byte[16], 0, 16));
  }
}
