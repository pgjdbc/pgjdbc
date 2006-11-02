/*-------------------------------------------------------------------------
*
* Copyright (c) 2006, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL$
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

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
    public static int parseSingleQuotes(final char[] query, int offset) {
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
        if (offset + 1 < query.length)
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
     * Checks if a character is valid as the second or latter character of a
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
     * If the length is zero, the result is true, if and only if the offsets
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
