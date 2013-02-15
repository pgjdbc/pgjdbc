package org.postgresql.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.postgresql.core.Encoding;

public class HStoreConverter {
   public static Map fromBytes(byte[] b, Encoding encoding) throws SQLException {
       Map m = new HashMap();
       int pos = 0;
       int numElements = ByteConverter.int4(b, pos); pos+=4;
       try {
           for (int i = 0; i < numElements; ++i) {
               int keyLen = ByteConverter.int4(b, pos); pos += 4;
               String key = encoding.decode(b, pos, keyLen); pos += keyLen;
               int valLen = ByteConverter.int4(b, pos); pos += 4;
               String val;
               if (valLen == -1) {
                   val = null;
               } else {
                   val = encoding.decode(b, pos, valLen); pos += valLen;
               }
               m.put(key, val);
           }
       }
       catch (IOException ioe)
       {
           throw new PSQLException(GT.tr("Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."), PSQLState.DATA_ERROR, ioe);
       }
       return m;
   }
   
   public static byte[] toBytes(Map m, Encoding encoding) throws SQLException {
       ByteArrayOutputStream baos = new ByteArrayOutputStream(4 + 10 * m.size());
       byte[] lenBuf = new byte[4];
       try {
           ByteConverter.int4(lenBuf, 0, m.size()); baos.write(lenBuf);
           for (Iterator i = m.entrySet().iterator(); i.hasNext(); ) {
               Entry e = (Entry) i.next();
               byte[] key = encoding.encode(e.getKey().toString());
               ByteConverter.int4(lenBuf, 0, key.length); baos.write(lenBuf);
               baos.write(key);
               
               if (e.getValue() == null) {
                   ByteConverter.int4(lenBuf, 0, -1); baos.write(lenBuf);
               } else {
                   byte[] val = encoding.encode(e.getValue().toString());
                   ByteConverter.int4(lenBuf, 0, val.length); baos.write(lenBuf);
                   baos.write(val);
               }
           }
       }
       catch (IOException ioe)
       {
           throw new PSQLException(GT.tr("Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."), PSQLState.DATA_ERROR, ioe);
       }
       return baos.toByteArray();
   }

   public static String toString(Map map) throws SQLException {
       if (map.isEmpty()) {
           return "";
       }
       StringBuffer sb = new StringBuffer(map.size() * 8);
       for (Iterator i = map.entrySet().iterator(); i.hasNext(); ) {
           Entry e = (Entry) i.next();
           appendEscaped(sb, e.getKey());
           sb.append("=>");
           appendEscaped(sb, e.getValue());
           sb.append(", ");
       }
       sb.setLength(sb.length() - 2);
       return sb.toString();
   }

   private static void appendEscaped(StringBuffer sb, Object val) throws SQLException {
      if (val != null) {
          sb.append('"');
          String s = val.toString();
          for (int pos = 0; pos < s.length(); pos++) {
              char ch = s.charAt(pos);
              if (ch == '"' || ch == '\\') {
                  sb.append('\\');
              }
              sb.append(ch);
          }
          sb.append('"');
      } else {
          sb.append("NULL");
      }
   }

   public static Map fromString(String s) {
       Map m = new HashMap();
       int pos = 0;
       StringBuffer sb = new StringBuffer();
       while (pos < s.length()) {
           sb.setLength(0);
           int start = s.indexOf('"', pos);
           int end = appendUntilQuote(sb, s, start);
           String key = sb.toString();
           pos = end + 3;
           
           String val;
           if (s.charAt(pos) == 'N') {
               val = null;
               pos += 4; 
           } else {
               sb.setLength(0);
               end = appendUntilQuote(sb, s, pos);
               val = sb.toString();
               pos = end;
           }
           pos++;
           m.put(key, val);
       }
       return m;
   }

   private static int appendUntilQuote(StringBuffer sb, String s, int pos) {
       for (pos += 1; pos < s.length(); pos++) {
           char ch = s.charAt(pos);
           if (ch == '"') {
               break;
           }
           if (ch == '\\') {
               pos++;
               ch = s.charAt(pos);
           }
           sb.append(ch);
       }
       return pos;
   }
}