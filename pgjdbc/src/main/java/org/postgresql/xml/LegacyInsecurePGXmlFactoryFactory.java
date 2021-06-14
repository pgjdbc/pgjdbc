/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xml;

import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;

public class LegacyInsecurePGXmlFactoryFactory implements PGXmlFactoryFactory {
  public static final LegacyInsecurePGXmlFactoryFactory INSTANCE = new LegacyInsecurePGXmlFactoryFactory();

  private LegacyInsecurePGXmlFactoryFactory() {
  }

  @Override
  public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    builder.setErrorHandler(NullErrorHandler.INSTANCE);
    return builder;
  }

  @Override
  public TransformerFactory newTransformerFactory() {
    return TransformerFactory.newInstance();
  }

  @Override
  public SAXTransformerFactory newSAXTransformerFactory() {
    return (SAXTransformerFactory) SAXTransformerFactory.newInstance();
  }

  @Override
  public XMLInputFactory newXMLInputFactory() {
    return XMLInputFactory.newInstance();
  }

  @Override
  public XMLOutputFactory newXMLOutputFactory() {
    return XMLOutputFactory.newInstance();
  }

  @Override
  public XMLReader createXMLReader() throws SAXException {
    return XMLReaderFactory.createXMLReader();
  }
}
