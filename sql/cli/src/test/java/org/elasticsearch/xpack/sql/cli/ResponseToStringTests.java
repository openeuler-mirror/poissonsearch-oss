/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.cli.net.protocol.QueryInitResponse;
import org.elasticsearch.xpack.sql.cli.net.protocol.QueryPageResponse;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ResponseToStringTests extends ESTestCase {
    public void testQueryInitResponse() {
        AttributedStringBuilder s = ResponseToString.toAnsi(new QueryInitResponse(123, "", "some command response"));
        assertEquals("some command response", unstyled(s));
        assertEquals("[37msome command response[0m", fullyStyled(s));
    }

    public void testQueryPageResponse() {
        AttributedStringBuilder s = ResponseToString.toAnsi(new QueryPageResponse(123, "", "some command response"));
        assertEquals("some command response", unstyled(s));
        assertEquals("[37msome command response[0m", fullyStyled(s));
    }

    private String unstyled(AttributedStringBuilder s) {
        Terminal dumb = mock(Terminal.class);
        when(dumb.getType()).thenReturn(Terminal.TYPE_DUMB);
        return s.toAnsi(dumb);
    }

    private String fullyStyled(AttributedStringBuilder s) {
        return s
                // toAnsi without an argument returns fully styled
                .toAnsi()
                // replace the escape character because they do not show up in the exception message
                .replace("\u001B", "");
    }
}
