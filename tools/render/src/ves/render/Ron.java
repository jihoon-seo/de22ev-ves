/*
 * ves.render: offline engine-sound renderer (host tool, not shipped to the car).
 * Reads the RON engine configs (.esc) of DasEtwas/enginesound
 * <https://github.com/DasEtwas/enginesound>, upstream commit e5fcca587397c0c8ba9c9d24874b951fed74d260.
 * Copyright (c) 2020 DasEtwas. MIT License; the full notice is in THIRD_PARTY.md.
 * The Java port and its throttle extension are MIT as well.
 */
package ves.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RON reader for the subset the .esc files use: anonymous structs {@code (key: value, ..)},
 * lists {@code [..]}, numbers and bare identifiers, // and block comments, trailing commas.
 * Structs become LinkedHashMap (file order), lists ArrayList, scalars their source text.
 */
final class Ron {
    private final String s;
    private final String source;
    private int p;

    private Ron(String s, String source) {
        this.s = s;
        this.source = source;
    }

    static Object parse(String text, String source) {
        Ron r = new Ron(text, source);
        Object v = r.value();
        r.ws();
        if (r.p != r.s.length()) {
            throw r.err("trailing data");
        }
        return v;
    }

    private IllegalArgumentException err(String msg) {
        int line = 1;
        for (int i = 0; i < p && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException(source + ":" + line + ": RON: " + msg);
    }

    private void ws() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isWhitespace(c)) {
                p++;
            } else if (s.startsWith("//", p)) {
                while (p < s.length() && s.charAt(p) != '\n') {
                    p++;
                }
            } else if (s.startsWith("/*", p)) {
                int e = s.indexOf("*/", p + 2);
                if (e < 0) {
                    throw err("unterminated comment");
                }
                p = e + 2;
            } else {
                return;
            }
        }
    }

    private boolean eat(char c) {
        ws();
        if (p < s.length() && s.charAt(p) == c) {
            p++;
            return true;
        }
        return false;
    }

    private Object value() {
        ws();
        if (p >= s.length()) {
            throw err("unexpected end");
        }
        char c = s.charAt(p);
        if (c == '[') {
            p++;
            List<Object> list = new ArrayList<Object>();
            while (!eat(']')) {
                list.add(value());
                if (!eat(',')) {
                    if (!eat(']')) {
                        throw err("expected , or ]");
                    }
                    break;
                }
            }
            return list;
        }
        if (c == '(') {
            p++;
            return structOrTuple();
        }
        String tok = token();
        ws();
        if (p < s.length() && s.charAt(p) == '(') { // named struct Name(..): drop the name
            p++;
            return structOrTuple();
        }
        return tok;
    }

    private Object structOrTuple() {
        ws();
        int save = p;
        boolean isStruct = false;
        if (p < s.length() && (Character.isLetter(s.charAt(p)) || s.charAt(p) == '_')) {
            token();
            isStruct = eat(':');
            p = save;
        }
        if (isStruct) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            while (!eat(')')) {
                String key = token();
                if (!eat(':')) {
                    throw err("expected : after " + key);
                }
                if (map.put(key, value()) != null) {
                    throw err("duplicate field " + key);
                }
                if (!eat(',')) {
                    if (!eat(')')) {
                        throw err("expected , or )");
                    }
                    break;
                }
            }
            return map;
        }
        List<Object> tuple = new ArrayList<Object>();
        while (!eat(')')) {
            tuple.add(value());
            if (!eat(',')) {
                if (!eat(')')) {
                    throw err("expected , or )");
                }
                break;
            }
        }
        return tuple;
    }

    private String token() {
        ws();
        int start = p;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '+') {
                p++;
            } else {
                break;
            }
        }
        if (p == start) {
            throw err("unexpected character '" + s.charAt(p) + "'");
        }
        return s.substring(start, p);
    }
}
