package org.postgresql.jdbc2;

import java.util.HashMap;
import java.util.Map;

/**
 * Array assistants register here
 *
 * @author Minglei Tu
 */
public class ArrayAssistantRegistry {
    private static Map arrayAssistantMap = new HashMap();

    public static ArrayAssistant getAssistant(int oid) {
        return (ArrayAssistant) arrayAssistantMap.get(new Integer(oid));
    }

    ////
    public static void register(int oid, ArrayAssistant assistant) {
        arrayAssistantMap.put(new Integer(oid), assistant);
    }
}
