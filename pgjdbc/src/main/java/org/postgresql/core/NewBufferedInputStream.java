package org.postgresql.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

public class NewBufferedInputStream extends BufferedInputStream {
  public NewBufferedInputStream(InputStream in) {
    super(in);
  }

  public NewBufferedInputStream(InputStream in, int size) {
    super(in, size);
    ;
  }


  public int peek() throws SocketTimeoutException, IOException {
    if (super.available() > 0 ) {
      return super.buf[pos];
    }else {
      return -1;
    }
  }

}
