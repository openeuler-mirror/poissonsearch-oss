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

package org.elasticsearch.index.rankeval;

import org.elasticsearch.common.text.Text;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

public class RatedSearchHitTests extends ESTestCase {

    public static RatedSearchHit randomRatedSearchHit() {
        Optional<Integer> rating = randomBoolean() ? Optional.empty() : Optional.of(randomIntBetween(0, 5));
        SearchHit searchHit = new InternalSearchHit(randomIntBetween(0, 10), randomAsciiOfLength(10), new Text(randomAsciiOfLength(10)),
                Collections.emptyMap());
        RatedSearchHit ratedSearchHit = new RatedSearchHit(searchHit, rating);
        return ratedSearchHit;
    }

    private static RatedSearchHit mutateTestItem(RatedSearchHit original) {
        Optional<Integer> rating = original.getRating();
        InternalSearchHit hit = (InternalSearchHit) original.getSearchHit();
        switch (randomIntBetween(0, 1)) {
        case 0:
            rating = rating.isPresent() ? Optional.of(rating.get() + 1) : Optional.of(randomInt(5));
            break;
        case 1:
            hit = new InternalSearchHit(hit.docId(), hit.getId() + randomAsciiOfLength(10), new Text(hit.getType()),
                    Collections.emptyMap());
            break;
        default:
            throw new IllegalStateException("The test should only allow two parameters mutated");
        }
        return new RatedSearchHit(hit, rating);
    }

    public void testSerialization() throws IOException {
        RatedSearchHit original = randomRatedSearchHit();
        RatedSearchHit deserialized = RankEvalTestHelper.copy(original, RatedSearchHit::new);
        assertEquals(deserialized, original);
        assertEquals(deserialized.hashCode(), original.hashCode());
        assertNotSame(deserialized, original);
    }

    public void testEqualsAndHash() throws IOException {
        RatedSearchHit testItem = randomRatedSearchHit();
        RankEvalTestHelper.testHashCodeAndEquals(testItem, mutateTestItem(testItem),
                RankEvalTestHelper.copy(testItem, RatedSearchHit::new));
    }
}
