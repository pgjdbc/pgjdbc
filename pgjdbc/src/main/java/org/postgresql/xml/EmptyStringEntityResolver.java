/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xml;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;

public class EmptyStringEntityResolver implements EntityResolver {
  public static final EmptyStringEntityResolver INSTANCE = new EmptyStringEntityResolver();

  @Override
  public InputSource resolveEntity(@Nullable String publicId, String systemId)
      throws SAXException, IOException {
    return new InputSource(new StringReader(""));
  }
}
