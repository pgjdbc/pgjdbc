/*-------------------------------------------------------------------------
*
* Copyright (c) 2006-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core;

/**
 * Basic query parser infrastructure.
 * 
 * @author Michael Paesold (mpaesold@gmx.at)
 */
public class Parser {

    /**
     * Find the end of the single-quoted string starting at the given offset.
     * 
     * Note: for <tt>'single '' quote in string'</tt>, this method currently
     * returns the offset of first <tt>'</tt> character after the initial
     * one. The caller must call the method a second time for the second
     * part of the quoted string.
     */
    public static int parseSingleQuotes(final char[] query, int offset,
                                        boolean standardConformingStrings) {
        // check for escape string syntax (E'')
        if (standardConformingStrings
                && offset >= 2
                && (query[offset-1] == 'e' || query[offset-1] == 'E')
                && charTerminatesIdentifier(query[offset-2]))
        {
            standardConformingStrings = false;
        }
        
        if (standardConformingStrings)
        {
            // do NOT treat backslashes as escape characters
            while (++offset < query.length)
            {
                switch (query[offset])
                {
                case '\'':
                    return offset;
                default:
                    break;
                }
            }
        }
        else
        {
            // treat backslashes as escape characters
            while (++offset < query.length)
            {
                switch (query[offset])
                {
                case '\\':
                    ++offset;
                    break;
                case '\'':
                    return offset;
                default:
                    break;
                }
            }
        }
        
        return query.length;
    }

    /**
     * Find the end of the double-quoted string starting at the given offset.
     *
     * Note: for <tt>&quot;double &quot;&quot; quote in string&quot;</tt>,
     * this method currently returns the offset of first <tt>&quot;</tt>
     * character after the initial one. The caller must call the method a
     * second time for the second part of the quoted string.
     */
    public static int parseDoubleQuotes(final char[] query, int offset) {
        while (++offset < query.length && query[offset] != '"') ;
        return offset;
    }

    /**
     * Test if the dollar character (<tt>$</tt>) at the given offset starts
     * a dollar-quoted string and return the offset of the ending dollar
     * character.
     */
    public static int parseDollarQuotes(final char[] query, int offset) {
        if (offset + 1 < query.length
                && (offset == 0 || !isIdentifierContChar(query[offset-1])))
        {
            int endIdx = -1;
            if (query[offset + 1] == '$')
                endIdx = offset + 1;
            else if (isDollarQuoteStartChar(query[offset + 1]))
            {
                for (int d = offset + 2; d < query.length; ++d)
                {
                    if (query[d] == '$')
                    {
                        endIdx = d;
                        break;
                    }
                    else if (!isDollarQuoteContChar(query[d]))
                        break;
                }
            }
            if (endIdx > 0)
            {
                // found; note: tag includes start and end $ character
                int tagIdx = offset, tagLen = endIdx - offset + 1;
                offset = endIdx; // loop continues at endIdx + 1
                for (++offset; offset < query.length; ++offset)
                {
                    if (query[offset] == '$' &&
                        subArraysEqual(query, tagIdx, offset, tagLen))
                    {
                        offset += tagLen - 1;
                        break;
                    }
                }
            }
        }        
        return offset;
    }

    /**
     * Test if the <tt>-</tt> character at <tt>offset</tt> starts a
     * <tt>--</tt> style line comment, and return the position of the first
     * <tt>\r</tt> or <tt>\n</tt> character.
     */
    public static int parseLineComment(final char[] query, int offset) {
        if (offset + 1 < query.length && query[offset + 1] == '-')
        {
            while (++offset < query.length)
            {
                if (query[offset] == '\r' || query[offset] == '\n')
                    break;
            }
        }
        return offset;
    }

    /**
     * Test if the <tt>/</tt> character at <tt>offset</tt> starts a block
     * comment, and return the position of the last <tt>/</tt> character.
     */
    public static int parseBlockComment(final char[] query, int offset) {
        if (offset + 1 < query.length && query[offset + 1] == '*')
        {
            // /* /* */ */ nest, according to SQL spec
            int level = 1;
            for (offset += 2; offset < query.length; ++offset)
            {
                switch (query[offset-1])
                {
                case '*':
                    if (query[offset] == '/')
                    {
                        --level;
                        ++offset; // don't parse / in */* twice
                    }
                    break;
                case '/':
                    if (query[offset] == '*')
                    {
                        ++level;
                        ++offset; // don't parse * in /*/ twice
                    }
                    break;
                default:
                    break;
                }

                if (level == 0)
                {
                    --offset; // reset position to last '/' char
                    break;
                }
            }
        }
        return offset;
    }
    
    /**
     * @return true if the character is a whitespace character as defined
     *         in the backend's parser
     */
    public static boolean isSpace(char c) {
       return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
    
    /**
     * @return true if the given character is a valid character for an
     *         operator in the backend's parser
     */
    public static boolean isOperatorChar(char c) {
        /*
         * Extracted from operators defined by {self} and {op_chars}
         * in pgsql/src/backend/parser/scan.l.
         */
        return ",()[].;:+-*/%^<>=~!@#&|`?".indexOf(c) != -1;
    }

    /**
     * Checks if a character is valid as the start of an identifier.
     * 
     * @param c the character to check
     * @return true if valid as first character of an identifier; false if not
     */
    public static boolean isIdentifierStartChar(char c) {
        /*
         * Extracted from {ident_start} and {ident_cont} in
         * pgsql/src/backend/parser/scan.l:
         * ident_start    [A-Za-z\200-\377_]
         * ident_cont     [A-Za-z\200-\377_0-9\$]
         */
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || c == '_' || c > 127 ;
    }
    
    /**
     * Checks if a character is valid as the second or later character of an
     * identifier.
     * 
     * @param c the character to check
     * @return true if valid as second or later character of an identifier; false if not
     */
    public static boolean isIdentifierContChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || c == '_' || c > 127
                || (c >= '0' && c <= '9')
                || c == '$';
    }
    
    /**
     * @return true if the character terminates an identifier
     */
    public static boolean charTerminatesIdentifier(char c) {
        return c == '"' || isSpace(c) || isOperatorChar(c);
    }

    /**
     * Checks if a character is valid as the start of a dollar quoting tag.
     * 
     * @param c the character to check
     * @return true if valid as first character of a dollar quoting tag; false if not
     */
    public static boolean isDollarQuoteStartChar(char c) {
        /*
         * The allowed dollar quote start and continuation characters
         * must stay in sync with what the backend defines in
         * pgsql/src/backend/parser/scan.l
         */
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || c == '_' || c > 127;
    }

    /**
     * Checks if a character is valid as the second or later character of a
     * dollar quoting tag.
     * 
     * @param c the character to check
     * @return true if valid as second or later character of a dollar quoting tag;
     *         false if not
     */
    public static boolean isDollarQuoteContChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || c == '_' || c > 127
                || (c >= '0' && c <= '9');
    }

    /**
     * Compares two sub-arrays of the given character array for equalness.
     * If the length is zero, the result is true if and only if the offsets
     * are within the bounds of the array.
     * 
     * @param arr  a char array
     * @param offA first sub-array start offset
     * @param offB second sub-array start offset
     * @param len  length of the sub arrays to compare
     * @return     true if the sub-arrays are equal; false if not
     */
    private static boolean subArraysEqual(final char[] arr,
                                          final int offA, final int offB,
                                          final int len) {
        if (offA < 0 || offB < 0
                || offA >= arr.length || offB >= arr.length
                || offA + len > arr.length || offB + len > arr.length)
            return false;
        
        for (int i = 0; i < len; ++i)
        {
            if (arr[offA + i] != arr[offB + i])
                return false;
        }
    
        return true;
    }
}
