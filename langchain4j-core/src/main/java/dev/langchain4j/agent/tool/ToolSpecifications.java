package dev.langchain4j.agent.tool;

import dev.langchain4j.model.output.structured.Description;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static dev.langchain4j.agent.tool.JsonSchemaProperty.*;
import static dev.langchain4j.internal.Utils.isNullOrBlank;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;

/**
 * Utility methods for {@link ToolSpecification}s.
 */
public class ToolSpecifications {

    private ToolSpecifications() {
    }

    /**
     * Returns {@link ToolSpecification}s for all methods annotated with @{@link Tool} within the specified class.
     *
     * @param classWithTools the class.
     * @return the {@link ToolSpecification}s.
     */
    public static List<ToolSpecification> toolSpecificationsFrom(Class<?> classWithTools) {
        return stream(classWithTools.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(ToolSpecifications::toolSpecificationFrom)
                .collect(toList());
    }

    /**
     * Returns {@link ToolSpecification}s for all methods annotated with @{@link Tool}
     * within the class of the specified object.
     *
     * @param objectWithTools the object.
     * @return the {@link ToolSpecification}s.
     */
    public static List<ToolSpecification> toolSpecificationsFrom(Object objectWithTools) {
        return toolSpecificationsFrom(objectWithTools.getClass());
    }

    /**
     * Returns the {@link ToolSpecification} for the given method annotated with @{@link Tool}.
     *
     * @param method the method.
     * @return the {@link ToolSpecification}.
     */
    public static ToolSpecification toolSpecificationFrom(Method method) {
        Tool annotation = method.getAnnotation(Tool.class);

        String name = isNullOrBlank(annotation.name()) ? method.getName() : annotation.name();
        String description = String.join("\n", annotation.value()); // TODO provide null instead of "" ?

        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(name)
                .description(description);

        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(ToolMemoryId.class)) {
                continue;
            }

            boolean required = Optional.ofNullable(parameter.getAnnotation(P.class))
                    .map(P::required)
                    .orElse(true);

            if (required) {
                builder.addParameter(parameter.getName(), toJsonSchemaProperties(parameter));
            } else {
                builder.addOptionalParameter(parameter.getName(), toJsonSchemaProperties(parameter));
            }
        }

        return builder.build();
    }

    /**
     * Convert a {@link Parameter} to a {@link JsonSchemaProperty}.
     *
     * @param parameter the parameter.
     * @return the {@link JsonSchemaProperty}.
     */
    static Iterable<JsonSchemaProperty> toJsonSchemaProperties(Parameter parameter) {

        Class<?> type = parameter.getType();

        P annotation = parameter.getAnnotation(P.class);
        JsonSchemaProperty description = annotation == null ? null : description(annotation.value());

        Iterable<JsonSchemaProperty> simpleType = toJsonSchemaProperties(type, description);

        if (simpleType != null) {
            return simpleType;
        }

        if (Collection.class.isAssignableFrom(type)) {
            return removeNulls(ARRAY, arrayTypeFrom(parameter.getParameterizedType()), description);
        }


        return removeNulls(OBJECT, schema(type), description);
    }

    static JsonSchemaProperty schema(Class<?> structured){
        return schema(structured, new HashSet<>());
    }

    private static JsonSchemaProperty schema(Class<?> structured, Set<Class<?>> visited) {
        if (visited.contains(structured)) {
            return null;
        }

        visited.add(structured);
        Map<String,Object> properties = new HashMap<>();
        for (Field field : structured.getDeclaredFields()) {
            String name = field.getName();
            if ( name.equals("this$0") || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                // Skip inner class reference.
                continue;
            }
            Iterable<JsonSchemaProperty> schemaProperties = toJsonSchemaProperties(field, visited);
            Map<Object,Object> objectMap = new HashMap<>();
            for(JsonSchemaProperty jsonSchemaProperty : schemaProperties) {
                objectMap.put(jsonSchemaProperty.key(), jsonSchemaProperty.value());
            }
            properties.put(name, objectMap);
        }
        return from( "properties", properties );
    }

    private static Iterable<JsonSchemaProperty> toJsonSchemaProperties(Field field, Set<Class<?>> visited) {

        Class<?> type = field.getType();

        Description annotation = field.getAnnotation(Description.class);
        JsonSchemaProperty description = annotation == null ? null : description(String.join(" ", annotation.value()));

        Iterable<JsonSchemaProperty> simpleType = toJsonSchemaProperties(type, description);

        if (simpleType != null) {
            return simpleType;
        }

        if (Collection.class.isAssignableFrom(type)) {
            return removeNulls(ARRAY, arrayTypeFrom((Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0]), description);
        }

        return removeNulls(OBJECT, schema(type, visited), description);
    }

    private static Iterable<JsonSchemaProperty> toJsonSchemaProperties(Class<?> type, JsonSchemaProperty description) {

        if (type == String.class) {
            return removeNulls(STRING, description);
        }

        if (isBoolean(type)) {
            return removeNulls(BOOLEAN, description);
        }

        if (isInteger(type)) {
            return removeNulls(INTEGER, description);
        }

        if (isNumber(type)) {
            return removeNulls(NUMBER, description);
        }

        if (type.isArray()) {
            return removeNulls(ARRAY, arrayTypeFrom(type.getComponentType()), description);
        }

        if (type.isEnum()) {
            return removeNulls(STRING, enums((Class<?>) type), description);
        }

        return null;
    }


    private static JsonSchemaProperty arrayTypeFrom(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
            if (actualTypeArguments.length == 1) {
                return arrayTypeFrom((Class<?>) actualTypeArguments[0]);
            }
        }
        return items(JsonSchemaProperty.OBJECT);
    }

    // TODO put constraints on min and max?
    private static boolean isNumber(Class<?> type) {
        return type == float.class || type == Float.class
                || type == double.class || type == Double.class
                || type == BigDecimal.class;
    }

    private static boolean isInteger(Class<?> type) {
        return type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == BigInteger.class;
    }

    private static boolean isBoolean(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private static JsonSchemaProperty arrayTypeFrom(Class<?> clazz) {
        if (clazz == String.class) {
            return items(JsonSchemaProperty.STRING);
        }
        if (isBoolean(clazz)) {
            return items(JsonSchemaProperty.BOOLEAN);
        }
        if (isInteger(clazz)) {
            return items(JsonSchemaProperty.INTEGER);
        }
        if (isNumber(clazz)) {
            return items(JsonSchemaProperty.NUMBER);
        }
        return objectItems(schema(clazz));
    }

    /**
     * Remove nulls from the given array.
     *
     * @param items the array
     * @return an iterable of the non-null items.
     */
    static Iterable<JsonSchemaProperty> removeNulls(JsonSchemaProperty... items) {
        return stream(items)
                .filter(Objects::nonNull)
                .collect(toList());
    }
}
