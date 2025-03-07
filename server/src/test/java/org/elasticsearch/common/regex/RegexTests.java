/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.common.regex;

import org.apache.logging.log4j.util.Strings;
import org.elasticsearch.test.ESTestCase;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.equalTo;

public class RegexTests extends ESTestCase {
    public void testFlags() {
        String[] supportedFlags = new String[]{"CASE_INSENSITIVE", "MULTILINE", "DOTALL", "UNICODE_CASE", "CANON_EQ", "UNIX_LINES",
                "LITERAL", "COMMENTS", "UNICODE_CHAR_CLASS", "UNICODE_CHARACTER_CLASS"};
        int[] flags = new int[]{Pattern.CASE_INSENSITIVE, Pattern.MULTILINE, Pattern.DOTALL, Pattern.UNICODE_CASE, Pattern.CANON_EQ,
                Pattern.UNIX_LINES, Pattern.LITERAL, Pattern.COMMENTS, Regex.UNICODE_CHARACTER_CLASS};
        Random random = random();
        int num = 10 + random.nextInt(100);
        for (int i = 0; i < num; i++) {
            int numFlags = random.nextInt(flags.length + 1);
            int current = 0;
            StringBuilder builder = new StringBuilder();
            for (int j = 0; j < numFlags; j++) {
                int index = random.nextInt(flags.length);
                current |= flags[index];
                builder.append(supportedFlags[index]);
                if (j < numFlags - 1) {
                    builder.append("|");
                }
            }
            String flagsToString = Regex.flagsToString(current);
            assertThat(Regex.flagsFromString(builder.toString()), equalTo(current));
            assertThat(Regex.flagsFromString(builder.toString()), equalTo(Regex.flagsFromString(flagsToString)));
            Pattern.compile("\\w\\d{1,2}", current); // accepts the flags?
        }
    }

    public void testDoubleWildcardMatch() {
        assertTrue(Regex.simpleMatch("ddd", "ddd"));
        assertTrue(Regex.simpleMatch("ddd", "Ddd", true));
        assertFalse(Regex.simpleMatch("ddd", "Ddd"));
        assertTrue(Regex.simpleMatch("d*d*d", "dadd"));
        assertTrue(Regex.simpleMatch("**ddd", "dddd"));
        assertTrue(Regex.simpleMatch("**ddD", "dddd", true));
        assertFalse(Regex.simpleMatch("**ddd", "fff"));
        assertTrue(Regex.simpleMatch("fff*ddd", "fffabcddd"));
        assertTrue(Regex.simpleMatch("fff**ddd", "fffabcddd"));
        assertFalse(Regex.simpleMatch("fff**ddd", "fffabcdd"));
        assertTrue(Regex.simpleMatch("fff*******ddd", "fffabcddd"));
        assertTrue(Regex.simpleMatch("fff*******ddd", "FffAbcdDd", true));
        assertFalse(Regex.simpleMatch("fff*******ddd", "FffAbcdDd", false));
        assertFalse(Regex.simpleMatch("fff******ddd", "fffabcdd"));
        assertTrue(Regex.simpleMatch("abCDefGH******ddd", "abCDefGHddd", false));
        assertTrue(Regex.simpleMatch("******", "a"));
        assertTrue(Regex.simpleMatch("***WILDcard***", "aaaaaaaaWILDcardZZZZZZ", false));
        assertFalse(Regex.simpleMatch("***xxxxx123456789xxxxxx***", "xxxxxabcdxxxxx", false));
        assertFalse(Regex.simpleMatch("***xxxxxabcdxxxxx***", "xxxxxABCDxxxxx", false));
        assertTrue(Regex.simpleMatch("***xxxxxabcdxxxxx***", "xxxxxABCDxxxxx", true));
        assertTrue(Regex.simpleMatch("**stephenIsSuperCool**", "ItIsTrueThatStephenIsSuperCoolSoYouShouldLetThisIn", true));
        assertTrue(
            Regex.simpleMatch(
                "**w**X**y**Z**",
                "abcdeFGHIJKLMNOPqrstuvwabcdeFGHIJKLMNOPqrstuvwXabcdeFGHIJKLMNOPqrstuvwXyabcdeFGHIJKLMNOPqrstuvwXyZ",
                false
            )
        );
    }

    public void testSimpleMatch() {
        for (int i = 0; i < 1000; i++) {
            final String matchingString = randomAlphaOfLength(between(0, 50));

            // construct a pattern that matches this string by repeatedly replacing random substrings with '*' characters
            String pattern = matchingString;
            for (int shrink = between(0, 5); shrink > 0; shrink--) {
                final int shrinkStart = between(0, pattern.length());
                final int shrinkEnd = between(shrinkStart, pattern.length());
                pattern = pattern.substring(0, shrinkStart) + "*" + pattern.substring(shrinkEnd);
            }
            assertTrue("[" + pattern + "] should match [" + matchingString + "]", Regex.simpleMatch(pattern, matchingString));
            assertTrue("[" + pattern + "] should match [" + matchingString.toUpperCase(Locale.ROOT) + "]",
                Regex.simpleMatch(pattern, matchingString.toUpperCase(Locale.ROOT), true));

            // construct a pattern that does not match this string by inserting a non-matching character (a digit)
            final int insertPos = between(0, pattern.length());
            pattern = pattern.substring(0, insertPos) + between(0, 9) + pattern.substring(insertPos);
            assertFalse("[" + pattern + "] should not match [" + matchingString + "]", Regex.simpleMatch(pattern, matchingString));
        }
    }

    public void testArbitraryWildcardMatch() {
        final String prefix = randomAlphaOfLengthBetween(1, 20);
        final String suffix = randomAlphaOfLengthBetween(1, 20);
        final String pattern1 = Strings.repeat("*", randomIntBetween(1, 1000));
        // dd***
        assertTrue(Regex.simpleMatch(prefix + pattern1, prefix + randomAlphaOfLengthBetween(10, 20), randomBoolean()));
        // ***dd
        assertTrue(Regex.simpleMatch(pattern1 + suffix, randomAlphaOfLengthBetween(10, 20) + suffix, randomBoolean()));
        // dd***dd
        assertTrue(Regex.simpleMatch(prefix + pattern1 + suffix, prefix + randomAlphaOfLengthBetween(10, 20) + suffix, randomBoolean()));
        // dd***dd***dd
        final String middle = randomAlphaOfLengthBetween(1, 20);
        final String pattern2 = Strings.repeat("*", randomIntBetween(1, 1000));
        assertTrue(
            Regex.simpleMatch(
                prefix + pattern1 + middle + pattern2 + suffix,
                prefix + randomAlphaOfLengthBetween(10, 20) + middle + randomAlphaOfLengthBetween(10, 20) + suffix,
                randomBoolean()
            )
        );
    }
}
