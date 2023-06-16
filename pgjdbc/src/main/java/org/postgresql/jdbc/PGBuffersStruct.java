package org.postgresql.jdbc;

/*
import org.postgresql.system.Context;
import org.postgresql.system.CustomTypes;
 */
import org.postgresql.types.CompositeType;
import org.postgresql.types.Type;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

import java.sql.Struct;

/*
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
*/

public class PGBuffersStruct {

  public static class Binary {
    public static Struct encode(PGStruct pgStruct) {
      return new PGStruct(pgStruct.context, pgStruct.typeName, pgStruct.attributeTypes);
    }
  }

  /*
  public static class Binary extends PGBuffersStruct<ByteBuf> {

    public static final ByteBufAllocator ALLOC = new UnpooledByteBufAllocator(false, true);

    public static Binary encode(Context context, CompositeType type, Object[] values) throws SQLException, IOException {

      Type[] attributeTypes = new Type[values.length];
      ByteBuf[] attributeBuffers = new ByteBuf[values.length];

      for (int attributeIdx = 0; attributeIdx < attributeBuffers.length; ++attributeIdx) {

        ByteBuf attributeBuffer = ALLOC.buffer();
        Object value = values[attributeIdx];
        if (value == null) {
          attributeTypes[attributeIdx] = context.getRegistry().loadBaseType("text");
          attributeBuffers[attributeIdx] = null;
          continue;
        }

        Type attributeType = JDBCTypeMapping.getType(JDBCTypeMapping.getSQLType(value), value, context.getRegistry());
        if (attributeType == null) {
          throw new IOException("Unable to determine type of attribute " + (attributeIdx + 1));
        }

        attributeType.getBinaryCodec().getEncoder()
            .encode(context, type, values[attributeIdx], null, attributeBuffer);

        attributeTypes[attributeIdx] = attributeType;
        attributeBuffers[attributeIdx] = attributeBuffer;
      }

      return new Binary(context, type.getQualifiedName().toString(), attributeTypes, attributeBuffers);
    }

    public Binary(Context context, String typeName, Type[] attributeTypes, ByteBuf[] attributeBuffers) {
      super(context, typeName, attributeTypes, attributeBuffers);
    }

    @Override
    protected Object getAttribute(Context context, Type type, ByteBuf buffer) throws IOException {
      Class<?> targetClass = CustomTypes.lookupCustomType(type, context.getCustomTypeMap(), null);
      return type.getBinaryCodec().getDecoder().decode(context, type, type.getLength(), null, buffer, targetClass, null);
    }

  }

  public static class Text extends PGBuffersStruct<CharSequence> {

    public Text(Context context, String typeName, Type[] attributeTypes, CharSequence[] attributeBuffers) {
      super(context, typeName, attributeTypes, attributeBuffers);
    }

    @Override
    protected Object getAttribute(Context context, Type type, CharSequence buffer) throws IOException {
      Class<?> targetClass = CustomTypes.lookupCustomType(type, context.getCustomTypeMap(), null);
      return type.getTextCodec().getDecoder().decode(context, type, type.getLength(), null, buffer, targetClass, null);
    }

  }


  private Buffer[] attributeBuffers;

  private PGBuffersStruct(Context context, String typeName, Type[] attributeTypes, Buffer[] attributeBuffers) {
    super(context, typeName, attributeTypes);
    this.attributeBuffers = attributeBuffers;
  }

  protected abstract Object getAttribute(Context context, Type type, Buffer buffer) throws IOException;

  @Override
  public Object[] getAttributes(Context context) throws IOException {

    Object[] attributeValues = new Object[attributeBuffers.length];

    for (int attributeIdx = 0; attributeIdx < attributeValues.length; ++attributeIdx) {
      Buffer attributeBuffer = attributeBuffers[attributeIdx];
      if (attributeBuffer == null) {
        attributeValues[attributeIdx] = null;
      }
      else {
        attributeValues[attributeIdx] = getAttribute(context, attributeTypes[attributeIdx], attributeBuffer);
      }
    }

    return attributeValues;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PGBuffersStruct struct = (PGBuffersStruct) o;
    return Objects.equals(context, struct.context) &&
        Objects.equals(typeName, struct.typeName) &&
        Arrays.equals(attributeTypes, struct.attributeTypes) &&
        Arrays.equals(attributeBuffers, struct.attributeBuffers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(context, typeName, attributeTypes, attributeBuffers);
  }
  */
}
