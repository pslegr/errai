package org.jboss.errai.marshalling.rebind.api.impl;

import com.google.gwt.json.client.JSONObject;
import org.jboss.errai.codegen.framework.Cast;
import org.jboss.errai.codegen.framework.Parameter;
import org.jboss.errai.codegen.framework.Statement;
import org.jboss.errai.codegen.framework.builder.AnonymousClassStructureBuilder;
import org.jboss.errai.codegen.framework.builder.BlockBuilder;
import org.jboss.errai.codegen.framework.meta.MetaClass;
import org.jboss.errai.codegen.framework.meta.MetaClassFactory;
import org.jboss.errai.codegen.framework.meta.MetaConstructor;
import org.jboss.errai.codegen.framework.meta.MetaField;
import org.jboss.errai.codegen.framework.util.Bool;
import org.jboss.errai.codegen.framework.util.GenUtil;
import org.jboss.errai.codegen.framework.util.Implementations;
import org.jboss.errai.codegen.framework.util.Stmt;
import org.jboss.errai.common.client.protocols.SerializationParts;
import org.jboss.errai.marshalling.client.api.MappedOrdered;
import org.jboss.errai.marshalling.client.api.MapsTo;
import org.jboss.errai.marshalling.client.api.Marshaller;
import org.jboss.errai.marshalling.client.api.MarshallingSession;
import org.jboss.errai.marshalling.client.api.exceptions.InvalidMappingException;
import org.jboss.errai.marshalling.client.api.exceptions.NoAvailableMarshallerException;
import org.jboss.errai.marshalling.rebind.api.MappingContext;
import org.jboss.errai.marshalling.rebind.api.MappingStrategy;
import org.jboss.errai.marshalling.rebind.api.ObjectMapper;
import org.jboss.errai.marshalling.rebind.util.MarshallingUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.jboss.errai.codegen.framework.meta.MetaClassFactory.parameterizedAs;
import static org.jboss.errai.codegen.framework.meta.MetaClassFactory.typeParametersOf;

/**
 * The Errai default Java-to-JSON-to-Java marshaling strategy.
 *
 * @author Mike Brock <cbrock@redhat.com>
 */
public class DefaultJavaMappingStrategy implements MappingStrategy {
  private MappingContext context;
  private MetaClass toMap;

  public DefaultJavaMappingStrategy(MappingContext context, MetaClass toMap) {
    this.context = context;
    this.toMap = toMap;
  }

  @Override
  public ObjectMapper getMapper() {
    if (isJavaBean(toMap)) {
      return generateJavaBeanMapper();
    }
    else {
      return generateImmutableMapper();
    }
  }

  private ObjectMapper generateImmutableMapper() {
    final ConstructorMapping mapping = findUsableConstructorMapping();

    final List<Statement> marshallers = new ArrayList<Statement>();
    for (FieldMapping m : mapping.getMappings()) {
      if (context.hasMarshaller(m.getType())) {
        marshallers.add(fieldDemarshall(m, JSONObject.class));
      }
      else {
        System.out.println();
      }
    }

    return new ObjectMapper() {
      @Override
      public Statement getMarshaller() {
        AnonymousClassStructureBuilder classStructureBuilder
                = Stmt.create(context.getCodegenContext())
                .newObject(parameterizedAs(Marshaller.class, typeParametersOf(JSONObject.class, toMap))).extend();

        classStructureBuilder.publicOverridesMethod("getTypeHandled")
                .append(Stmt.load(toMap).returnValue())
                .finish();

        classStructureBuilder.publicOverridesMethod("getEncodingType")
                .append(Stmt.load("json").returnValue())
                .finish();

        classStructureBuilder.publicOverridesMethod("demarshall",
                Parameter.of(Object.class, "a0"), Parameter.of(MarshallingSession.class, "a1"))
                .append(Stmt.nestedCall(Stmt.newObject(toMap)
                        .withParameters(marshallers.toArray(new Object[marshallers.size()]))).returnValue())
                .finish();

        BlockBuilder<?> marshallMethodBlock = classStructureBuilder.publicOverridesMethod("marshall",
                Parameter.of(Object.class, "a0"), Parameter.of(MarshallingSession.class, "a1"));

        marshallToJSON(marshallMethodBlock, toMap);

        marshallMethodBlock.finish();

        classStructureBuilder.publicOverridesMethod("handles", Parameter.of(Object.class, "a0"))
                .append(Stmt.nestedCall(Bool.and(
                        Bool.notEquals(Stmt.loadVariable("a0").invoke("isObject"), null),
                        Stmt.loadVariable("a0").invoke("isObject").invoke("get", SerializationParts.ENCODED_TYPE)
                                .invoke("equals", Stmt.loadVariable("this").invoke("getTypeHandled").invoke("getName"))
                )).returnValue()).finish();

        return classStructureBuilder.finish();
      }
    };

  }

  private ObjectMapper generateJavaBeanMapper() {
    return null;
  }

  private ConstructorMapping findUsableConstructorMapping() {
    Set<MetaConstructor> constructors = new HashSet<MetaConstructor>();
    List<FieldMapping> mappings = new ArrayList<FieldMapping>();

    for (MetaConstructor c : toMap.getConstructors()) {
      if (c.isAnnotationPresent(MappedOrdered.class)) {
        constructors.add(c);
      }
      else if (c.getParameters().length != 0) {
        boolean satisifed = true;
        FieldScan:
        for (int i = 0; i < c.getParameters().length; i++) {
          Annotation[] annotations = c.getParameters()[i].getAnnotations();
          if (annotations.length == 0) {
            satisifed = false;
          }
          else {
            for (Annotation a : annotations) {
              if (!MapsTo.class.isAssignableFrom(a.annotationType())) {
                satisifed = false;
                break FieldScan;
              }
              else {
                MapsTo mapsTo = (MapsTo) a;
                String fieldName = mapsTo.value();

                if (toMap.getDeclaredField(fieldName) == null) {
                  throw new InvalidMappingException(MapsTo.class.getCanonicalName()
                          + " refers to a field ('" + fieldName + "') which does not exist in the class: "
                          + toMap.getName());
                }


                mappings.add(new FieldMapping(fieldName, c.getParameters()[i].getType()));
              }
            }
          }
        }

        if (satisifed) {
          constructors.add(c);
        }
      }
    }

    return new ConstructorMapping(constructors.iterator().next(), ConstructionType.Mapped, mappings);
  }

  private static enum ConstructionType {
    Mapped, Custom
  }

  private static class ConstructorMapping {
    MetaConstructor constructor;
    ConstructionType type;
    List<FieldMapping> mappings;

    private ConstructorMapping(MetaConstructor constructor, ConstructionType type, List<FieldMapping> mappings) {
      this.constructor = constructor;
      this.type = type;
      this.mappings = mappings;
    }

    public MetaConstructor getConstructor() {
      return constructor;
    }

    public ConstructionType getType() {
      return type;
    }

    public List<FieldMapping> getMappings() {
      return mappings;
    }
  }

  private static class FieldMapping {
    String fieldName;
    MetaClass type;

    private FieldMapping(String fieldName, MetaClass type) {
      this.fieldName = fieldName;
      this.type = type;
    }

    public String getFieldName() {
      return fieldName;
    }

    public MetaClass getType() {
      return type;
    }
  }

  private boolean isJavaBean(MetaClass toMap) {
    return toMap.getConstructor(new MetaClass[0]) != null;
  }

  public Statement fieldDemarshall(FieldMapping mapping, Class<?> fromType) {
     return fieldDemarshall(mapping, MetaClassFactory.get(fromType));
   }
  
  
  public Statement fieldDemarshall(FieldMapping mapping, MetaClass fromType) {
    return fieldDemarshall(mapping.getFieldName(), fromType, mapping.getType());
  }

  public Statement fieldDemarshall(String fieldName, MetaClass fromType, MetaClass toType) {
    return unwrapJSON(Stmt.nestedCall(Cast.to(fromType, Stmt.loadVariable("a0"))).invoke("get", fieldName), toType);
  }

  public void marshallToJSON(BlockBuilder<?> builder, MetaClass toType) {
    if (!context.hasProvidedOrGeneratedMarshaller(toType)) {
      throw new NoAvailableMarshallerException(toType.getName());
    }

    Implementations.StringBuilderBuilder sb = Implementations.newStringBuilder();
    sb.append("{");
    sb.append(keyValue(SerializationParts.ENCODED_TYPE, string(toType.getName())));
    sb.append(",");
    sb.append(string(SerializationParts.OBJECT_ID)).append(":").append(Stmt.loadVariable("a0").invoke("hashCode"));

    boolean hasEncoded = false;

    for (MetaField metaField : toType.getDeclaredFields()) {
      if (metaField.isTransient()) {
        continue;
      }

      if (!hasEncoded) {
        sb.append(",");
        hasEncoded = true;
      }


      MetaClass targetType = GenUtil.getPrimitiveWrapper(metaField.getType());
//      boolean array = targetType.isArray();
//      int dims = array ? GenUtil.getArrayDimensions(targetType) : 0;
//
//      if (array) {
//        while (targetType.isArray()) {
//          targetType = targetType.getComponentType();
//        }
//      }
//
      if (!context.hasProvidedOrGeneratedMarshaller(targetType.getFullyQualifiedName())) {
        throw new NoAvailableMarshallerException(targetType.getFullyQualifiedName());
      }

      sb.append("\"" + metaField.getName() + "\" : ");
      sb.append(Stmt.loadVariable(MarshallingUtil.getVarName(targetType.getFullyQualifiedName()))
              .invoke("marshall", valueAccessorFor(metaField), Stmt.loadVariable("a1")));
    }

    sb.append("}");
    builder.append(Stmt.nestedCall(sb).invoke("toString"));
  }


  private static String keyValue(String key, String value) {
    return "\"" + key + "\":" + value + "";
  }

  private static String string(String value) {
    return "\"" + value + "\"";
  }

  public Statement valueAccessorFor(MetaField field) {
    if (!field.isPublic()) {
      GenUtil.addPrivateAccessStubs(true, context.getClassStructureBuilder(), field, field.getDeclaringClass());
      return Stmt.invokeStatic(context.getGeneratedBootstrapClass(), GenUtil.getPrivateFieldInjectorName(field),
              Stmt.loadVariable("a0"));
    }
    else {
      return Stmt.loadStatic(field.getDeclaringClass(), field.getName());
    }
  }

  public Statement unwrapJSON(Statement valueStatement, MetaClass toType) {
    return Stmt.create(context.getCodegenContext())
            .loadVariable(MarshallingUtil.getVarName(toType))
            .invoke("demarshall", valueStatement, Stmt.loadVariable("a1"));
  }
}