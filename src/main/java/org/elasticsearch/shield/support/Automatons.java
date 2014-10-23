/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.support;

import org.apache.lucene.util.automaton.Automata;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.Operations;
import org.apache.lucene.util.automaton.RegExp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.apache.lucene.util.automaton.MinimizationOperations.minimize;
import static org.apache.lucene.util.automaton.Operations.*;

/**
 *
 */
public final class Automatons {

    static final char WILDCARD_STRING = '*';     // String equality with support for wildcards
    static final char WILDCARD_CHAR = '?';       // Char equality with support for wildcards
    static final char WILDCARD_ESCAPE = '\\';    // Escape character

    private Automatons() {
    }

    /**
     * Builds and returns an automaton that will represent the union of all the given patterns.
     */
    public static Automaton patterns(String... patterns) {
        if (patterns.length == 0) {
            return Automata.makeEmpty();
        }
        Automaton automaton = pattern(patterns[0]);
        for (String pattern : patterns) {
            automaton = union(automaton, pattern(pattern));
        }
        return determinize(minimize(automaton));
    }

    /**
     * Builds and returns an automaton that will represent the union of all the given patterns.
     */
    public static Automaton patterns(Collection<String> patterns) {
        if (patterns.isEmpty()) {
            return Automata.makeEmpty();
        }
        Automaton automaton = null;
        for (String pattern : patterns) {
            if (automaton == null) {
                automaton = pattern(pattern);
            } else {
                automaton = union(automaton, pattern(pattern));
            }
        }
        return determinize(minimize(automaton));
    }

    /**
     * Builds and returns an automaton that represents the given pattern.
     */
    static Automaton pattern(String pattern) {
        if (pattern.startsWith("/")) { // it's a lucene regexp
            if (pattern.length() == 1 || !pattern.endsWith("/")) {
                throw new IllegalArgumentException("Invalid pattern [" + pattern + "]. Patterns starting with '/' " +
                        "indicate regular expression pattern and therefore must also end with '/'." +
                        " Other patterns (those that do not start with '/') will be treated as simple wildcard patterns");
            }
            String regex = pattern.substring(1, pattern.length() - 1);
            return new RegExp(regex).toAutomaton();
        }
        return wildcard(pattern);
    }

    /**
     * Builds and returns an automaton that represents the given pattern.
     */
    static Automaton wildcard(String text) {
        List<Automaton> automata = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            final int c = text.codePointAt(i);
            int length = Character.charCount(c);
            switch(c) {
                case WILDCARD_STRING:
                    automata.add(Automata.makeAnyString());
                    break;
                case WILDCARD_CHAR:
                    automata.add(Automata.makeAnyChar());
                    break;
                case WILDCARD_ESCAPE:
                    // add the next codepoint instead, if it exists
                    if (i + length < text.length()) {
                        final int nextChar = text.codePointAt(i + length);
                        length += Character.charCount(nextChar);
                        automata.add(Automata.makeChar(nextChar));
                        break;
                    } // else fallthru, lenient parsing with a trailing \
                default:
                    automata.add(Automata.makeChar(c));
            }
            i += length;
        }
        return Operations.concatenate(automata);
    }

    public static Automaton unionAndDeterminize(Automaton a1, Automaton a2) {
        return determinize(union(a1, a2));
    }

    public static Automaton minusAndDeterminize(Automaton a1, Automaton a2) {
        return determinize(minus(a1, a2));
    }
}
