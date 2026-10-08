/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fastpath;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.ParameterList;
import org.postgresql.util.ByteStreamWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fastpath with no server behind it that records the payload length of every {@code lowrite}, so a
 * test can see how {@code LargeObject} splits a write.
 *
 * <p>Every call returns four zero bytes, which {@code LargeObject} reads as descriptor 0 from
 * {@code lo_open}. The payloads concatenated in arrival order are the contents of the large
 * object, since every {@code lowrite} continues where the previous one ended.</p>
 */
@SuppressWarnings("deprecation")
public class LowriteRecorder extends Fastpath {
  private final List<Integer> lowriteLengths = new ArrayList<>();
  private final ByteArrayOutputStream contents = new ByteArrayOutputStream();
  private final boolean keepContents;
  private int failingLowrite;
  private SQLException failure = new SQLException("unused");

  /**
   * Creates a recorder.
   *
   * @param keepContents whether to keep the payloads; a test that sends about 1 GiB records the
   *        lengths alone
   */
  public LowriteRecorder(boolean keepContents) {
    super((BaseConnection) Proxy.newProxyInstance(
        LowriteRecorder.class.getClassLoader(),
        new Class<?>[]{BaseConnection.class},
        (proxy, method, args) -> null));
    this.keepContents = keepContents;
  }

  /**
   * Makes the {@code lowrite} with the given 1-based number throw {@code failure} instead of
   * recording its payload.
   */
  public void failLowrite(int number, SQLException failure) {
    this.failingLowrite = number;
    this.failure = failure;
  }

  /**
   * Returns the payload length of every recorded {@code lowrite}, in arrival order.
   */
  public List<Integer> lowriteLengths() {
    return lowriteLengths;
  }

  public byte[] contents() {
    return contents.toByteArray();
  }

  @Override
  public byte[] fastpath(String name, FastpathArg[] args) throws SQLException {
    if ("lowrite".equals(name)) {
      if (lowriteLengths.size() + 1 == failingLowrite) {
        throw failure;
      }
      args[1].populateParameter(payloadRecorder(), 2);
    }
    return new byte[4];
  }

  private ParameterList payloadRecorder() {
    return (ParameterList) Proxy.newProxyInstance(
        LowriteRecorder.class.getClassLoader(),
        new Class<?>[]{ParameterList.class},
        (proxy, method, args) -> {
          if (!"setBytea".equals(method.getName())) {
            throw new UnsupportedOperationException(method.toString());
          }
          if (args[1] instanceof ByteStreamWriter) {
            ByteStreamWriter writer = (ByteStreamWriter) args[1];
            lowriteLengths.add(writer.getLength());
            if (keepContents) {
              writeTo(writer);
            }
          } else {
            int len = (Integer) args[3];
            lowriteLengths.add(len);
            if (keepContents) {
              contents.write((byte[]) args[1], (Integer) args[2], len);
            }
          }
          return null;
        });
  }

  private void writeTo(ByteStreamWriter writer) throws IOException {
    writer.writeTo(() -> contents);
  }
}
