/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.util;

import org.apache.lucene.search.spell.LevensteinDistance;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toList;

public abstract class StringUtils {

    public static final String EMPTY = "";
    public static final String NEW_LINE = "\n";

    private static final int TO_STRING_LIMIT = 52;

    public static String limitedToString(Object o) {
        String s = String.valueOf(o);
        return s.length() > TO_STRING_LIMIT ? s.substring(0, TO_STRING_LIMIT).concat("...") : s;
    }

    public static <E> String limitedToString(Collection<E> c) {
        Iterator<E> it = c.iterator();
        if (!it.hasNext())
            return "[]";

        // ..]
        StringBuilder sb = new StringBuilder(TO_STRING_LIMIT + 4);
        sb.append('[');
        for (;;) {
            E e = it.next();
            String next = e == c ? "(this Collection)" : String.valueOf(e);
            if (next.length() + sb.length() > TO_STRING_LIMIT) {
                sb.append(next.substring(0, Math.max(0, TO_STRING_LIMIT - sb.length())));
                sb.append('.').append('.').append(']');
                return sb.toString();
            }
            else {
                sb.append(next);
            }
            if (!it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }

    //CamelCase to camel_case
    public static String camelCaseToUnderscore(String string) {
        if (!Strings.hasText(string)) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        String s = string.trim();

        boolean previousCharWasUp = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isAlphabetic(ch)) {
                if (Character.isUpperCase(ch)) {
                    if (i > 0 && !previousCharWasUp) {
                        sb.append("_");
                    }
                    previousCharWasUp = true;
                }
                else {
                    previousCharWasUp = (ch == '_');
                }
            }
            else {
                previousCharWasUp = true;
            }
            sb.append(ch);
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    public static String nullAsEmpty(String string) {
        return string == null ? EMPTY : string;
    }

    // % -> *
    // _ -> .
    // consider \ as an escaping char
    public static String sqlToJavaPattern(CharSequence sqlPattern, char escapeChar, boolean shouldEscape) {
        StringBuilder regex = new StringBuilder(sqlPattern.length() + 4);

        boolean escaped = false;
        regex.append('^');
        for (int i = 0; i < sqlPattern.length(); i++) {
            char curr = sqlPattern.charAt(i);
            if (shouldEscape && !escaped && (curr == escapeChar)) {
                escaped = true;
                regex.append(curr);
            }
            else {
                switch (curr) {
                    case '%':
                        regex.append(escaped ? "%" : ".*");
                        escaped = false;
                        break;
                    case '_':
                        regex.append(escaped ? "_" : ".");
                        escaped = false;
                        break;
                    default:
                        // escape special regex characters
                        switch (curr) {
                            case '\\':
                            case '^':
                            case '$':
                            case '.':
                            case '*':
                            case '?':
                            case '+':
                            case '|':
                            case '(':
                            case ')':
                            case '[':
                            case ']':
                            case '{':
                            case '}':
                                if (!escaped) {
                                    regex.append('\\');
                                }
                        }

                        regex.append(curr);
                        escaped = false;
                }
            }
        }
        regex.append('$');

        return regex.toString();
    }

    //TODO: likely this needs to be changed to probably its own indexNameResolver
    public static String jdbcToEsPattern(String sqlPattern) {
        if (Strings.hasText(sqlPattern)) {
            // the index might include a type - since we only support only support one type per index, remove the type
            int dotIndex = sqlPattern.indexOf(".");
            if (dotIndex >= 0) {
                sqlPattern = sqlPattern.substring(0, dotIndex);
            }
            return sqlPattern.replace('%', '*').replace('_', '*');
        }
        return EMPTY;
    }

    public static String sqlToJavaPattern(CharSequence sqlPattern) {
        return sqlToJavaPattern(sqlPattern, '\\', true);
    }

    public static Pattern likeRegex(String likePattern) {
        return Pattern.compile(sqlToJavaPattern(likePattern));
    }

    public static String toString(SearchSourceBuilder source) {
        try (XContentBuilder builder = XContentFactory.jsonBuilder().prettyPrint().humanReadable(true)) {
            source.toXContent(builder, ToXContent.EMPTY_PARAMS);
            return builder.string();
        } catch (IOException e) {
            throw new RuntimeException("error rendering", e);
        }
    }

    public static List<String> findSimilar(String match, Iterable<String> potentialMatches) {
        LevensteinDistance ld = new LevensteinDistance();
        List<Tuple<Float, String>> scoredMatches = new ArrayList<>();
        for (String potentialMatch : potentialMatches) {
            float distance = ld.getDistance(match, potentialMatch);
            if (distance >= 0.5f) {
                scoredMatches.add(new Tuple<>(distance, potentialMatch));
            }
        }
        CollectionUtil.timSort(scoredMatches, (a,b) -> b.v1().compareTo(a.v1()));
        return scoredMatches.stream()
                .map(a -> a.v2())
                .collect(toList());
    }
}