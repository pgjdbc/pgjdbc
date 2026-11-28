/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xml;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

class DefaultPGXmlFactoryFactoryTest {
  /**
   * The features are turned back on to emulate a SAX driver that refuses them. The external DTD
   * defines an entity, so the parse shows whether the DTD was read.
   */
  @Test
  void xmlReaderDoesNotReadAnExternalDtd() throws Exception {
    Path dtd = Files.createTempFile("external", ".dtd");
    try {
      Files.write(dtd, "<!ENTITY e \"from the external DTD\">".getBytes("UTF-8"));
      XMLReader reader = DefaultPGXmlFactoryFactory.INSTANCE.createXMLReader();
      reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
      reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", true);
      StringBuilder text = new StringBuilder();
      reader.setContentHandler(new DefaultHandler() {
        @Override
        public void characters(char[] ch, int start, int length) {
          text.append(ch, start, length);
        }
      });
      String uri = new File(dtd.toString()).toURI().toString();
      try {
        reader.parse(new InputSource(new StringReader(
            "<!DOCTYPE a SYSTEM \"" + uri + "\"><a>&e;</a>")));
      } catch (Exception e) {
        // The entity is undefined when the DTD is not read.
      }
      assertTrue(text.indexOf("from the external DTD") == -1, text.toString());
    } finally {
      Files.delete(dtd);
    }
  }
}
