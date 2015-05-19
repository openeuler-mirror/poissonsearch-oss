/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.xcontent;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.is;

/**
 *
 */
public class MapPathTests extends ElasticsearchTestCase {

    @Test
    public void testEval() throws Exception {
        Map<String, Object> map = ImmutableMap.<String, Object>builder()
                .put("key", "value")
                .build();

        assertThat(MapPath.eval("key", map), is((Object) "value"));
        assertThat(MapPath.eval("key1", map), nullValue());
    }

    @Test @Repeat(iterations = 5)
    public void testEval_List() throws Exception {
        List list = ImmutableList.of(1, 2, 3, 4);
        Map<String, Object> map = ImmutableMap.<String, Object>builder()
                .put("key", list)
                .build();

        int index = randomInt(3);
        assertThat(MapPath.eval("key." + index, map), is(list.get(index)));
    }

    @Test @Repeat(iterations = 5)
    public void testEval_Array() throws Exception {
        int[] array = new int[] { 1, 2, 3, 4 };
        Map<String, Object> map = ImmutableMap.<String, Object>builder()
                .put("key", array)
                .build();

        int index = randomInt(3);
        assertThat(((Number) MapPath.eval("key." + index, map)).intValue(), is(array[index]));
    }

    @Test
    public void testEval_Map() throws Exception {
        Map<String, Object> map = ImmutableMap.<String, Object>builder()
                .put("a", ImmutableMap.of("b", "val"))
                .build();

        assertThat(MapPath.eval("a.b", map), is((Object) "val"));
    }


    @Test
    public void testEval_Mixed() throws Exception {
        Map<String, Object> map = ImmutableMap.<String, Object>builder()
                .put("a", ImmutableMap.builder()
                        .put("b", ImmutableList.builder()
                                .add(ImmutableList.builder()
                                        .add(ImmutableMap.builder()
                                            .put("c", "val")
                                        .build())
                                .build())
                        .build())
                    .build())
                .build();

        assertThat(MapPath.eval("", map), is((Object) map));
        assertThat(MapPath.eval("a.b.0.0.c", map), is((Object) "val"));
        assertThat(MapPath.eval("a.b.0.0.c.d", map), nullValue());
        assertThat(MapPath.eval("a.b.0.0.d", map), nullValue());
        assertThat(MapPath.eval("a.b.c", map), nullValue());

    }
}
