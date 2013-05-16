package org.postgresql.jdbc4.array;

import org.postgresql.jdbc2.ArrayElementBuilder;
import org.postgresql.util.ByteConverter;

import java.util.UUID;

public class UUIDArrayElementBuilder implements ArrayElementBuilder {
    @Override
    public Class getElementClass() {
        return UUID.class;
    }

    @Override
    public Object buildElement(byte[] bytes, int pos, int len) {
        return new UUID(ByteConverter.int8(bytes, pos + 0), ByteConverter.int8(bytes, pos + 8));
    }

    @Override
    public Object buildElement(String literal) {
        return UUID.fromString(literal);
    }
}
