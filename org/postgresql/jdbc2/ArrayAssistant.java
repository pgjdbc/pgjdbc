package org.postgresql.jdbc2;

/**
 * Implement this interface and register the its instance to ArrayAssistantRegistry,
 * to let Postgres driver to support more array type
 *
 * @author Minglei Tu
 */
public interface ArrayAssistant {
    /**
     * get array base type
     *
     * @return
     */
    Class baseType();

    /**
     * build a array element from its binary bytes
     *
     * @param bytes
     * @param pos
     * @param len
     * @return
     */
    Object buildElement(byte[] bytes, int pos, int len);

    /**
     * build an array element from its literal string
     *
     * @param literal
     * @return
     */
    Object buildElement(String literal);
}
