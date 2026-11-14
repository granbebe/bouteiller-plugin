package io.jenkins.plugins.bouteiller.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer, enough for the small documents of the API and of the events (no dependency).
 *
 * <p>Objects are read as {@link Map}, arrays as {@link List}, numbers as {@link Long} or {@link BigDecimal}, and
 * {@code null}, booleans and strings as their Java counterparts.
 */
public final class Json {
    /** Nesting limit, against stack exhaustion by hostile documents. */
    private static final int MAX_DEPTH = 32;

    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a document; throws {@link IllegalArgumentException} when it is malformed. */
    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    /** Parses a document that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (parse(text) instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("a JSON object is expected");
    }

    // MARK: Reading

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("too deeply nested");
        }
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        char character = text.charAt(position);
        return switch (character) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("a key is expected");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue(depth + 1));
            skipWhitespace();
            char separator = next();
            if (separator == '}') {
                return map;
            }
            if (separator != ',') {
                throw error("',' or '}' is expected");
            }
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> list = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue(depth + 1));
            skipWhitespace();
            char separator = next();
            if (separator == ']') {
                return list;
            }
            if (separator != ',') {
                throw error("',' or ']' is expected");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            char character = next();
            if (character == '"') {
                return builder.toString();
            }
            if (character == '\\') {
                char escaped = next();
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("truncated escape");
                        }
                        try {
                            builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException ignored) {
                            throw error("invalid escape");
                        }
                        position += 4;
                    }
                    default -> throw error("invalid escape");
                }
            } else if (character < 0x20) {
                throw error("control character in a string");
            } else {
                builder.append(character);
            }
        }
    }

    private Object readNumber() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        String number = text.substring(start, position);
        if (number.isEmpty()) {
            throw error("unexpected character");
        }
        try {
            BigDecimal decimal = new BigDecimal(number);
            if (decimal.scale() <= 0 && number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                return decimal.longValueExact();
            }
            return decimal;
        } catch (NumberFormatException | ArithmeticException ignored) {
            throw error("invalid number");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, position)) {
            throw error("unexpected character");
        }
        position += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(position);
    }

    private char next() {
        char character = peek();
        position++;
        return character;
    }

    private void expect(char character) {
        if (next() != character) {
            throw error("'" + character + "' is expected");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Invalid JSON at " + position + ": " + message);
    }

    // MARK: Writing

    /** Serializes maps, lists, strings, numbers, booleans and {@code null}. */
    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(value, builder);
        return builder.toString();
    }

    private static void write(Object value, StringBuilder builder) {
        switch (value) {
            case null -> builder.append("null");
            case String string -> writeString(string, builder);
            case Boolean bool -> builder.append(bool);
            case Number number -> builder.append(number);
            case Map<?, ?> map -> {
                builder.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    writeString(String.valueOf(entry.getKey()), builder);
                    builder.append(':');
                    write(entry.getValue(), builder);
                }
                builder.append('}');
            }
            case List<?> list -> {
                builder.append('[');
                for (int index = 0; index < list.size(); index++) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    write(list.get(index), builder);
                }
                builder.append(']');
            }
            default -> throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeString(String string, StringBuilder builder) {
        builder.append('"');
        for (int index = 0; index < string.length(); index++) {
            char character = string.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }
}
