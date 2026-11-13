package ves.resynth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader (RFC 8259) for bake manifests: objects become LinkedHashMap&lt;String, Object&gt;, arrays
 * ArrayList&lt;Object&gt;, numbers Double, strings String, true/false Boolean, null null. Anything malformed
 * (trailing commas, leading zeros, bad escapes, text after the value, nesting deeper than 64) is an IOException.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) throws IOException {
        Json j = new Json(text);
        j.ws();
        Object v = j.value(0);
        j.ws();
        if (j.i != text.length()) throw j.err("text after the value");
        return v;
    }

    private IOException err(String what) {
        return new IOException("json: " + what + " at offset " + i);
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private boolean eat(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private Object value(int depth) throws IOException {
        if (depth > 64) throw err("nested too deep");
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object(int depth) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (eat('}')) return m;
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected a key");
            String k = string();
            ws();
            if (!eat(':')) throw err("expected ':'");
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (eat('}')) return m;
            if (!eat(',')) throw err("expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) throws IOException {
        List<Object> a = new ArrayList<Object>();
        i++;
        ws();
        if (eat(']')) return a;
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            if (eat(']')) return a;
            if (!eat(',')) throw err("expected ',' or ']'");
        }
    }

    private String string() throws IOException {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw err("control character in a string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw err("unterminated escape");
            char e = s.charAt(i++);
            int k = "\"\\/bfnrt".indexOf(e);
            if (k >= 0) b.append("\"\\/\b\f\n\r\t".charAt(k));
            else if (e == 'u' && i + 4 <= s.length() && s.substring(i, i + 4).matches("[0-9A-Fa-f]{4}")) {
                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                i += 4;
            } else throw err("bad escape");
        }
    }

    private Double number() throws IOException {
        int start = i;
        eat('-');
        if (eat('0')) {
            // a lone zero: no leading zeros
        } else if (i < s.length() && s.charAt(i) >= '1' && s.charAt(i) <= '9') digits();
        else throw err("not a value");
        if (eat('.') && digits() == 0) throw err("no digits after '.'");
        if (eat('e') || eat('E')) {
            if (!eat('+')) eat('-');
            if (digits() == 0) throw err("no exponent digits");
        }
        return Double.valueOf(s.substring(start, i));
    }

    private int digits() {
        int n = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
            n++;
        }
        return n;
    }
}
