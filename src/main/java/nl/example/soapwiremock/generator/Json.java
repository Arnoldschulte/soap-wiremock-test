package nl.example.soapwiremock.generator;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Minimale JSON-writer (Maps, Lists, Strings, getallen, booleans) met nette inspringing. */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            writeContainer(sb, indent, '{', '}', map.entrySet().iterator(), (entry, level) -> {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) entry;
                string(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), level);
            });
        } else if (value instanceof List<?> list) {
            writeContainer(sb, indent, '[', ']', list.iterator(), (item, level) -> write(sb, item, level));
        } else if (value instanceof String s) {
            string(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value == null) {
            sb.append("null");
        } else {
            throw new IllegalArgumentException("Niet ondersteund in JSON: " + value.getClass());
        }
    }

    private interface ItemWriter {
        void write(Object item, int level);
    }

    private static void writeContainer(StringBuilder sb, int indent, char open, char close,
                                       Iterator<?> items, ItemWriter writer) {
        if (!items.hasNext()) {
            sb.append(open).append(close);
            return;
        }
        sb.append(open).append('\n');
        while (items.hasNext()) {
            sb.append("  ".repeat(indent + 1));
            writer.write(items.next(), indent + 1);
            if (items.hasNext()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ".repeat(indent)).append(close);
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
