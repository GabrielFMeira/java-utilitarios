import jakarta.persistence.Tuple;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Utilitario para converter {@link Tuple} em classes comuns e records.
 */
public class TupleUtil {

    /**
     * Converte um {@link Tuple} para uma instancia de classe comum usando reflexao.
     * O mapeamento e feito por nome do campo/alias.
     *
     * @param tuple tuple de origem
     * @param clazz classe de destino
     * @param <T> tipo da classe de destino
     * @return instancia preenchida com os valores do tuple
     * @throws Exception quando nao for possivel instanciar ou preencher a classe
     */
    public static <T> T fromTuple(Tuple tuple, Class<T> clazz) throws Exception {
        Objects.requireNonNull(tuple, "tuple não pode ser null");
        Objects.requireNonNull(clazz, "clazz não pode ser null");

        T instance = clazz.getDeclaredConstructor().newInstance();
        var fields = getAllFields(clazz);

        for (Field field : fields) {
            if (shouldSkip(field)) {
                continue;
            }

            field.setAccessible(true);
            String fieldName = field.getName();
            Class<?> fieldType = field.getType();

            Object tupleValue;
            try {
                tupleValue = tuple.get(fieldName, getNormalizedFieldTypeClass(fieldType));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Alias '" + fieldName + "' nao encontrado no Tuple para a classe " + clazz.getSimpleName(), e
                );
            }

            Object converted = castFields(fieldType, tupleValue);
            if (converted == null && fieldType.isPrimitive()) {
                continue;
            }

            field.set(instance, converted);
        }

        return instance;
    }

    /**
     * Converte um {@link Tuple} para um record usando o construtor canonico.
     * O mapeamento e feito por nome do componente/alias.
     *
     * @param tuple tuple de origem
     * @param clazz record de destino
     * @param <T> tipo do record
     * @return record preenchido com os valores do tuple
     * @throws Exception quando nao for possivel montar os argumentos ou instanciar o record
     */
    public static <T> T fromTupleToRecord(Tuple tuple, Class<T> clazz) throws Exception {
        RecordComponent[] components = clazz.getRecordComponents();

        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            String name = component.getName();
            Class<?> type = component.getType();

            paramTypes[i] = type;

            Object tupleValue;
            try {
                tupleValue = tuple.get(name, getNormalizedFieldTypeClass(type));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Alias '" + name + "' nao encontrado no Tuple para o record " + clazz.getSimpleName(), e
                );
            }

            Object converted = castFields(type, tupleValue);

            if (converted == null && type.isPrimitive()) {
                throw new IllegalArgumentException(
                        "Componente primitivo '" + name + "' do record " + clazz.getSimpleName() + " veio null no Tuple"
                );
            }

            args[i] = converted;
        }

        Constructor<T> canonicalCtor = clazz.getDeclaredConstructor(paramTypes);
        canonicalCtor.setAccessible(true);
        return canonicalCtor.newInstance(args);
    }

    /**
     * Define se um campo deve ser ignorado no mapeamento.
     *
     * @param field campo analisado
     * @return true para campos sinteticos, estaticos ou finais
     */
    private static boolean shouldSkip(Field field) {
        int modifiers = field.getModifiers();
        return field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers);
    }

    /**
     * Retorna todos os campos declarados da classe e de suas superclasses.
     *
     * @param clazz classe base
     * @return lista com todos os campos encontrados
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * Normaliza o tipo do campo para o tipo esperado pelo {@link Tuple#get(String, Class)}.
     * Devem ser adicionados novos tipos de acordo com a necessidade.
     *
     * @param fieldType tipo do campo destino
     * @return tipo normalizado para leitura no tuple
     */
    private static Class<?> getNormalizedFieldTypeClass(Class<?> fieldType) {
        Class<?> normalized = wrapPrimitive(fieldType);

        if (normalized == String.class ||
            normalized == BigDecimal.class ||
            normalized == BigInteger.class ||
            normalized == Long.class ||
            normalized == Integer.class ||
            normalized == Boolean.class ||
            normalized == Double.class ||
            normalized == Float.class ||
            normalized == Short.class ||
            normalized == Byte.class) {
            return normalized;
        }

        if (normalized == LocalDateTime.class || normalized == Date.class) {
            return Timestamp.class;
        }

        throw new IllegalArgumentException("Tipo nao mapeado: " + fieldType.getName());
    }

    /**
     * Converte o valor lido do tuple para o tipo do campo de destino.
     *
     * @param fieldType tipo de destino
     * @param tupleValue valor de origem lido do tuple
     * @param <T> tipo final esperado
     * @return valor convertido
     */
    @SuppressWarnings("unchecked")
    private static <T> T castFields(Class<?> fieldType, Object tupleValue) {
        if (tupleValue == null) return null;

        Class<?> normalizedType = wrapPrimitive(fieldType);
        Class<?> tupleValueClass = tupleValue.getClass();

        if (normalizedType.isAssignableFrom(tupleValueClass)) {
            return (T) tupleValue;
        }

        if (tupleValue instanceof Number n) {
            if (normalizedType == Long.class) return (T) Long.valueOf(n.longValue());
            if (normalizedType == Integer.class) return (T) Integer.valueOf(n.intValue());
            if (normalizedType == BigDecimal.class) return (T) BigDecimal.valueOf(n.doubleValue());
            if (normalizedType == BigInteger.class) return (T) BigInteger.valueOf(n.longValue());
        }

        if (tupleValue instanceof Timestamp ts) {
            if (normalizedType == LocalDateTime.class) {
                return (T) ts.toLocalDateTime();
            }

            return (T) Date.from(ts.toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant());
        }

        throw new IllegalArgumentException(
                "Conversao nao suportada: valor=" + tupleValueClass.getName() + " -> campo=" + normalizedType.getName()
        );
    }

    /**
     * Converte tipos primitivos para seus wrappers.
     *
     * @param type tipo de entrada
     * @return tipo wrapper equivalente quando primitivo
     */
    private static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

}
