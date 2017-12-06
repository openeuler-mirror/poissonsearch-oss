/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli.command;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.cli.CliHttpClient;
import org.elasticsearch.xpack.sql.cli.TestTerminal;
import org.elasticsearch.xpack.sql.cli.net.protocol.QueryInitResponse;
import org.elasticsearch.xpack.sql.cli.net.protocol.QueryPageResponse;

import java.sql.SQLException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class ServerQueryCliCommandTests extends ESTestCase {

    public void testExceptionHandling() throws Exception {
        TestTerminal testTerminal = new TestTerminal();
        CliHttpClient client = mock(CliHttpClient.class);
        CliSession cliSession = new CliSession(client);
        when(client.queryInit("blah", 1000)).thenThrow(new SQLException("test exception"));
        ServerQueryCliCommand cliCommand = new ServerQueryCliCommand();
        assertTrue(cliCommand.handle(testTerminal, cliSession, "blah"));
        assertEquals("<b>Bad request [</b><i>test exception</i><b>]</b>\n", testTerminal.toString());
        verify(client, times(1)).queryInit(eq("blah"), eq(1000));
        verifyNoMoreInteractions(client);
    }

    public void testOnePageQuery() throws Exception {
        TestTerminal testTerminal = new TestTerminal();
        CliHttpClient client = mock(CliHttpClient.class);
        CliSession cliSession = new CliSession(client);
        cliSession.setFetchSize(10);
        when(client.queryInit("test query", 10)).thenReturn(new QueryInitResponse(123, "", "some command response"));
        ServerQueryCliCommand cliCommand = new ServerQueryCliCommand();
        assertTrue(cliCommand.handle(testTerminal, cliSession, "test query"));
        assertEquals("some command response<flush/>", testTerminal.toString());
        verify(client, times(1)).queryInit(eq("test query"), eq(10));
        verifyNoMoreInteractions(client);
    }

    public void testThreePageQuery() throws Exception {
        TestTerminal testTerminal = new TestTerminal();
        CliHttpClient client = mock(CliHttpClient.class);
        CliSession cliSession = new CliSession(client);
        cliSession.setFetchSize(10);
        when(client.queryInit("test query", 10)).thenReturn(new QueryInitResponse(123, "my_cursor1", "first"));
        when(client.nextPage("my_cursor1")).thenReturn(new QueryPageResponse(345, "my_cursor2", "second"));
        when(client.nextPage("my_cursor2")).thenReturn(new QueryPageResponse(678, "", "third"));
        ServerQueryCliCommand cliCommand = new ServerQueryCliCommand();
        assertTrue(cliCommand.handle(testTerminal, cliSession, "test query"));
        assertEquals("firstsecondthird<flush/>", testTerminal.toString());
        verify(client, times(1)).queryInit(eq("test query"), eq(10));
        verify(client, times(2)).nextPage(any());
        verifyNoMoreInteractions(client);
    }

    public void testTwoPageQueryWithSeparator() throws Exception {
        TestTerminal testTerminal = new TestTerminal();
        CliHttpClient client = mock(CliHttpClient.class);
        CliSession cliSession = new CliSession(client);
        cliSession.setFetchSize(15);
        // Set a separator
        cliSession.setFetchSeparator("-----");
        when(client.queryInit("test query", 15)).thenReturn(new QueryInitResponse(123, "my_cursor1", "first"));
        when(client.nextPage("my_cursor1")).thenReturn(new QueryPageResponse(345, "", "second"));
        ServerQueryCliCommand cliCommand = new ServerQueryCliCommand();
        assertTrue(cliCommand.handle(testTerminal, cliSession, "test query"));
        assertEquals("first-----\nsecond<flush/>", testTerminal.toString());
        verify(client, times(1)).queryInit(eq("test query"), eq(15));
        verify(client, times(1)).nextPage(any());
        verifyNoMoreInteractions(client);
    }

}