/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xml;

import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;

public interface PGXmlFactoryFactory {
  DocumentBuilder newDocumentBuilder() throws ParserConfigurationException;

  TransformerFactory newTransformerFactory();

  SAXTransformerFactory newSAXTransformerFactory();

  XMLInputFactory newXMLInputFactory();

  XMLOutputFactory newXMLOutputFactory();

  XMLReader createXMLReader() throws SAXException;
}
