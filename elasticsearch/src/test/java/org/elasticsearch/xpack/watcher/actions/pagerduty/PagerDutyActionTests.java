/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.actions.pagerduty;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.common.http.HttpProxy;
import org.elasticsearch.xpack.common.http.HttpRequest;
import org.elasticsearch.xpack.common.http.HttpResponse;
import org.elasticsearch.xpack.common.text.TextTemplate;
import org.elasticsearch.xpack.common.text.TextTemplateEngine;
import org.elasticsearch.xpack.notification.pagerduty.IncidentEvent;
import org.elasticsearch.xpack.notification.pagerduty.IncidentEventContext;
import org.elasticsearch.xpack.notification.pagerduty.IncidentEventDefaults;
import org.elasticsearch.xpack.notification.pagerduty.PagerDutyAccount;
import org.elasticsearch.xpack.notification.pagerduty.PagerDutyService;
import org.elasticsearch.xpack.notification.pagerduty.SentEvent;
import org.elasticsearch.xpack.watcher.actions.Action;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.execution.Wid;
import org.elasticsearch.xpack.watcher.watch.Payload;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.watcher.actions.ActionBuilders.pagerDutyAction;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.mockExecutionContextBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PagerDutyActionTests extends ESTestCase {

    private PagerDutyService service;

    @Before
    public void init() throws Exception {
        service = mock(PagerDutyService.class);
    }

    public void testExecute() throws Exception {
        final String accountName = "account1";

        TextTemplateEngine templateEngine = mock(TextTemplateEngine.class);

        TextTemplate description = new TextTemplate("_description");
        IncidentEvent.Template.Builder eventBuilder = new IncidentEvent.Template.Builder(description);
        boolean attachPayload = randomBoolean();
        eventBuilder.setAttachPayload(attachPayload);
        eventBuilder.setAccount(accountName);
        IncidentEvent.Template eventTemplate = eventBuilder.build();

        PagerDutyAction action = new PagerDutyAction(eventTemplate);
        ExecutablePagerDutyAction executable = new ExecutablePagerDutyAction(action, logger, service, templateEngine);

        Map<String, Object> data = new HashMap<>();
        Payload payload = new Payload.Simple(data);

        Map<String, Object> metadata = MapBuilder.<String, Object>newMapBuilder().put("_key", "_val").map();

        DateTime now = DateTime.now(DateTimeZone.UTC);

        Wid wid = new Wid(randomAsciiOfLength(5), randomLong(), now);
        WatchExecutionContext ctx = mockExecutionContextBuilder(wid.watchId())
                .wid(wid)
                .payload(payload)
                .time(wid.watchId(), now)
                .metadata(metadata)
                .buildMock();

        Map<String, Object> ctxModel = new HashMap<>();
        ctxModel.put("id", ctx.id().value());
        ctxModel.put("watch_id", wid.watchId());
        ctxModel.put("payload", data);
        ctxModel.put("metadata", metadata);
        ctxModel.put("execution_time", now);
        Map<String, Object> triggerModel = new HashMap<>();
        triggerModel.put("triggered_time", now);
        triggerModel.put("scheduled_time", now);
        ctxModel.put("trigger", triggerModel);
        ctxModel.put("vars", Collections.emptyMap());
        Map<String, Object> expectedModel = new HashMap<>();
        expectedModel.put("ctx", ctxModel);

        when(templateEngine.render(description, expectedModel)).thenReturn(description.getTemplate());

        IncidentEvent event = new IncidentEvent(description.getTemplate(), null, wid.watchId(), null, null, accountName, attachPayload,
                null, null);
        PagerDutyAccount account = mock(PagerDutyAccount.class);
        when(account.getDefaults()).thenReturn(new IncidentEventDefaults(Settings.EMPTY));
        HttpResponse response = mock(HttpResponse.class);
        when(response.status()).thenReturn(200);
        HttpRequest request = mock(HttpRequest.class);
        SentEvent sentEvent = SentEvent.responded(event, request, response);
        when(account.send(event, payload)).thenReturn(sentEvent);
        when(service.getAccount(accountName)).thenReturn(account);

        Action.Result result = executable.execute("_id", ctx, payload);

        assertThat(result, notNullValue());
        assertThat(result, instanceOf(PagerDutyAction.Result.Executed.class));
        assertThat(result.status(), equalTo(Action.Result.Status.SUCCESS));
        assertThat(((PagerDutyAction.Result.Executed) result).sentEvent(), sameInstance(sentEvent));
    }

    public void testParser() throws Exception {

        XContentBuilder builder = jsonBuilder().startObject();

        String accountName = randomAsciiOfLength(10);
        builder.field("account", accountName);

        TextTemplate incidentKey = null;
        if (randomBoolean()) {
            incidentKey = new TextTemplate("_incident_key");
            builder.field("incident_key", incidentKey);
        }

        TextTemplate description = null;
        if (randomBoolean()) {
            description = new TextTemplate("_description");
            builder.field("description", description);
        }

        TextTemplate client = null;
        if (randomBoolean()) {
            client = new TextTemplate("_client");
            builder.field("client", client);
        }

        TextTemplate clientUrl = null;
        if (randomBoolean()) {
            clientUrl = new TextTemplate("_client_url");
            builder.field("client_url", clientUrl);
        }

        TextTemplate eventType = null;
        if (randomBoolean()) {
            eventType = new TextTemplate(randomFrom("trigger", "resolve", "acknowledge"));
            builder.field("event_type", eventType);
        }

        Boolean attachPayload = randomBoolean() ? null : randomBoolean();
        if (attachPayload != null) {
            builder.field("attach_payload", attachPayload.booleanValue());
        }

        HttpProxy proxy = null;
        if (randomBoolean()) {
            proxy = new HttpProxy("localhost", 8080);
            proxy.toXContent(builder, ToXContent.EMPTY_PARAMS);
        }

        IncidentEventContext.Template[] contexts = null;
        if (randomBoolean()) {
            contexts = new IncidentEventContext.Template[] {
                    IncidentEventContext.Template.link(new TextTemplate("_href"), new TextTemplate("_text")),
                    IncidentEventContext.Template.image(new TextTemplate("_src"), new TextTemplate("_href"), new TextTemplate("_alt"))
            };
            builder.array("context", (Object) contexts);
        }

        builder.endObject();

        BytesReference bytes = builder.bytes();
        logger.info("pagerduty action json [{}]", bytes.utf8ToString());
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();

        PagerDutyAction action = PagerDutyAction.parse("_watch", "_action", parser);

        assertThat(action, notNullValue());
        assertThat(action.event.account, is(accountName));
        assertThat(action.event, notNullValue());
        assertThat(action.event, instanceOf(IncidentEvent.Template.class));
        assertThat(action.event, is(new IncidentEvent.Template(description, eventType, incidentKey, client, clientUrl, accountName,
                attachPayload, contexts, proxy)));
    }

    public void testParserSelfGenerated() throws Exception {
        IncidentEvent.Template.Builder event = IncidentEvent.templateBuilder(randomAsciiOfLength(50));

        if (randomBoolean()) {
            event.setIncidentKey(new TextTemplate(randomAsciiOfLength(50)));
        }
        if (randomBoolean()) {
            event.setClient(new TextTemplate(randomAsciiOfLength(50)));
        }
        if (randomBoolean()) {
            event.setClientUrl(new TextTemplate(randomAsciiOfLength(50)));
        }
        if (randomBoolean()) {
            event.setAttachPayload(randomBoolean());
        }
        if (randomBoolean()) {
            event.addContext(IncidentEventContext.Template.link(new TextTemplate("_href"), new TextTemplate("_text")));
        }
        if (randomBoolean()) {
            event.addContext(IncidentEventContext.Template.image(new TextTemplate("_src"), new TextTemplate("_href"),
                    new TextTemplate("_alt")));
        }
        if (randomBoolean()) {
            event.setEventType(new TextTemplate(randomAsciiOfLength(50)));
        }
        if (randomBoolean()) {
            event.setAccount(randomAsciiOfLength(50)).build();
        }
        if (randomBoolean()) {
            event.setProxy(new HttpProxy("localhost", 8080));
        }

        PagerDutyAction action = pagerDutyAction(event).build();
        XContentBuilder jsonBuilder = jsonBuilder();
        action.toXContent(jsonBuilder, ToXContent.EMPTY_PARAMS);
        XContentParser parser = JsonXContent.jsonXContent.createParser(jsonBuilder.bytes());
        parser.nextToken();

        PagerDutyAction parsedAction = PagerDutyAction.parse("_w1", "_a1", parser);
        assertThat(parsedAction, notNullValue());
        assertThat(parsedAction, is(action));
    }

    public void testParserInvalid() throws Exception {
        try {
            XContentBuilder builder = jsonBuilder().startObject().field("unknown_field", "value").endObject();
            XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
            parser.nextToken();
            PagerDutyAction.parse("_watch", "_action", parser);
            fail("Expected ElasticsearchParseException but did not happen");
        } catch (ElasticsearchParseException e) {

        }
    }
}
