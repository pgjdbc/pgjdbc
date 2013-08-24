package org.postgresql.jdbc2;

import java.util.HashMap;
import java.util.Map;

public class ArrayElementBuilderFactory {
    private static Map builderMap = new HashMap();

    public static void setArrayElementBuilder(int oid, ArrayElementBuilder builder) {
        if (builder == null) {
            builderMap.remove(new Integer(oid));
        } else {
            builderMap.put(new Integer(oid), builder);
        }
    }

    public static ArrayElementBuilder getArrayElementBuilder(int oid) {
        return (ArrayElementBuilder) builderMap.get(new Integer(oid));
    }
}
