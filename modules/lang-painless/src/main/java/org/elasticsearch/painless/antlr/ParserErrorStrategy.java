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

package org.elasticsearch.painless.antlr;

import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;

import java.text.ParseException;

/**
 * An error strategy that will override the default error behavior to fail on the first parser error.
 */
final class ParserErrorStrategy extends DefaultErrorStrategy {

    @Override
    public void recover(final Parser recognizer, final RecognitionException re) {
        final Token token = re.getOffendingToken();
        String message;

        if (token == null) {
            message = "Error: no parse token found.";
        } else if (re instanceof InputMismatchException) {
            message = "Error[" + token.getLine() + ":" + token.getCharPositionInLine() + "]:" +
                    " unexpected token [" + getTokenErrorDisplay(token) + "]" +
                    " was expecting one of [" + re.getExpectedTokens().toString(recognizer.getVocabulary()) + "].";
        } else if (re instanceof NoViableAltException) {
            if (token.getType() == PainlessParser.EOF) {
                message = "Error: unexpected end of script.";
            } else {
                message = "Error[" + token.getLine() + ":" + token.getCharPositionInLine() + "]:" +
                        "invalid sequence of tokens near [" + getTokenErrorDisplay(token) + "].";
            }
        } else {
            message = "Error[" + token.getLine() + ":" + token.getCharPositionInLine() + "]:" +
                    " unexpected token near [" + getTokenErrorDisplay(token) + "].";
        }

        final ParseException parseException = new ParseException(message, token == null ? -1 : token.getStartIndex());
        parseException.initCause(re);

        throw new RuntimeException(parseException);
    }

    @Override
    public Token recoverInline(final Parser recognizer) throws RecognitionException {
        final Token token = recognizer.getCurrentToken();
        final String message = "Error[" + token.getLine() + ":" + token.getCharPositionInLine() + "]:" +
            " unexpected token [" + getTokenErrorDisplay(token) + "]" +
            " was expecting one of [" + recognizer.getExpectedTokens().toString(recognizer.getVocabulary()) + "].";
        final ParseException parseException = new ParseException(message, token.getStartIndex());

        throw new RuntimeException(parseException);
    }

    @Override
    public void sync(final Parser recognizer) {
    }
}
