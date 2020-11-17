/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.xml;

import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;

/**
 * Default implementation of PGXmlFactoryFactory that configures each factory per OWASP recommendations.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html">https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html</a>
 */
public class DefaultPGXmlFactoryFactory implements PGXmlFactoryFactory {
  public static final DefaultPGXmlFactoryFactory INSTANCE = new DefaultPGXmlFactoryFactory();

  private DefaultPGXmlFactoryFactory() {
  }

  private DocumentBuilderFactory getDocumentBuilderFactory() {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    setFactoryProperties(factory);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }

  @Override
  public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilder builder = getDocumentBuilderFactory().newDocumentBuilder();
    builder.setEntityResolver(EmptyStringEntityResolver.INSTANCE);
    builder.setErrorHandler(NullErrorHandler.INSTANCE);
    return builder;
  }

  @Override
  public TransformerFactory newTransformerFactory() {
    TransformerFactory factory = TransformerFactory.newInstance();
    setFactoryProperties(factory);
    return factory;
  }

  @Override
  public SAXTransformerFactory newSAXTransformerFactory() {
    SAXTransformerFactory factory = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
    setFactoryProperties(factory);
    return factory;
  }

  @Override
  public XMLInputFactory newXMLInputFactory() {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    setPropertyQuietly(factory, XMLInputFactory.SUPPORT_DTD, false);
    setPropertyQuietly(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    return factory;
  }

  @Override
  public XMLOutputFactory newXMLOutputFactory() {
    XMLOutputFactory factory = XMLOutputFactory.newInstance();
    return factory;
  }

  @Override
  public XMLReader createXMLReader() throws SAXException {
    XMLReader factory = XMLReaderFactory.createXMLReader();
    setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
    setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
    setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
    factory.setErrorHandler(NullErrorHandler.INSTANCE);
    return factory;
  }

  private static void setFeatureQuietly(Object factory, String name, boolean value) {
    try {
      if (factory instanceof DocumentBuilderFactory) {
        ((DocumentBuilderFactory) factory).setFeature(name, value);
      } else if (factory instanceof TransformerFactory) {
        ((TransformerFactory) factory).setFeature(name, value);
      } else if (factory instanceof XMLReader) {
        ((XMLReader) factory).setFeature(name, value);
      } else {
        throw new Error("Invalid factory class: " + factory.getClass());
      }
      return;
    } catch (Exception ignore) {
    }
  }

  private static void setAttributeQuietly(Object factory, String name, Object value) {
    try {
      if (factory instanceof DocumentBuilderFactory) {
        ((DocumentBuilderFactory) factory).setAttribute(name, value);
      } else if (factory instanceof TransformerFactory) {
        ((TransformerFactory) factory).setAttribute(name, value);
      } else {
        throw new Error("Invalid factory class: " + factory.getClass());
      }
    } catch (Exception ignore) {
    }
  }

  private static void setFactoryProperties(Object factory) {
    setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
    setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
    setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
    setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
    // Values from XMLConstants inlined for JDK 1.6 compatibility
    setAttributeQuietly(factory, "http://javax.xml.XMLConstants/property/accessExternalDTD", "");
    setAttributeQuietly(factory, "http://javax.xml.XMLConstants/property/accessExternalSchema", "");
    setAttributeQuietly(factory, "http://javax.xml.XMLConstants/property/accessExternalStylesheet", "");
  }

  private static void setPropertyQuietly(Object factory, String name, Object value) {
    try {
      if (factory instanceof XMLReader) {
        ((XMLReader) factory).setProperty(name, value);
      } else if (factory instanceof XMLInputFactory) {
        ((XMLInputFactory) factory).setProperty(name, value);
      } else {
        throw new Error("Invalid factory class: " + factory.getClass());
      }
    } catch (Exception ignore) {
    }
  }
}
