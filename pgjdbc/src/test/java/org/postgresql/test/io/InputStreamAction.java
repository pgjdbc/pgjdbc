/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.io;

public interface InputStreamAction {
  InputStreamAction READ = new InputStreamAction() {
    @Override
    public String toString() {
      return "read()";
    }
  };

  InputStreamAction RESET = new InputStreamAction() {
    @Override
    public String toString() {
      return "reset()";
    }
  };

  class Skip implements InputStreamAction {
    public final int skip;

    public Skip(int skip) {
      this.skip = skip;
    }

    @Override
    public String toString() {
      return "skip(" + skip + ')';
    }
  }

  class Mark implements InputStreamAction {
    public final int readLimit;

    public Mark(int readLimit) {
      this.readLimit = readLimit;
    }

    @Override
    public String toString() {
      return "mark(" + readLimit + ')';
    }
  }

  class ReadOffsetLength implements InputStreamAction {
    public final int offset;
    public final int length;

    public ReadOffsetLength(int offset, int length) {
      this.offset = offset;
      this.length = length;
    }

    @Override
    public String toString() {
      return "read([]" + ", offset=" + offset + ", length=" + length + ')';
    }
  }
}
