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

import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.index.rankeval.RankedListQualityMetric.filterUnknownDocuments;

public class DiscountedCumulativeGainAtTests extends ESTestCase {

    /**
     * Assuming the docs are ranked in the following order:
     *
     * rank | rel_rank | 2^(rel_rank) - 1 | log_2(rank + 1)    | (2^(rel_rank) - 1) / log_2(rank + 1)
     * -------------------------------------------------------------------------------------------
     * 1    | 3        | 7.0              | 1.0                | 7.0
     * 2    | 2        | 3.0              | 1.5849625007211563 | 1.8927892607143721
     * 3    | 3        | 7.0              | 2.0                | 3.5
     * 4    | 0        | 0.0              | 2.321928094887362  | 0.0
     * 5    | 1        | 1.0              | 2.584962500721156  | 0.38685280723454163
     * 6    | 2        | 3.0              | 2.807354922057604  | 1.0686215613240666
     *
     * dcg = 13.84826362927298 (sum of last column)
     */
    public void testDCGAt() throws IOException, InterruptedException, ExecutionException {
        List<RatedDocument> rated = new ArrayList<>();
        int[] relevanceRatings = new int[] { 3, 2, 3, 0, 1, 2 };
        InternalSearchHit[] hits = new InternalSearchHit[6];
        for (int i = 0; i < 6; i++) {
            rated.add(new RatedDocument("index", "type", Integer.toString(i), relevanceRatings[i]));
            hits[i] = new InternalSearchHit(i, Integer.toString(i), new Text("type"), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new ShardId("index", "uuid", 0)));
        }
        DiscountedCumulativeGainAt dcg = new DiscountedCumulativeGainAt(6);
        assertEquals(13.84826362927298, dcg.evaluate("id", hits, rated).getQualityLevel(), 0.00001);

        /**
         * Check with normalization: to get the maximal possible dcg, sort documents by relevance in descending order
         *
         * rank | rel_rank | 2^(rel_rank) - 1 | log_2(rank + 1)    | (2^(rel_rank) - 1) / log_2(rank + 1)
         * -------------------------------------------------------------------------------------------
         * 1    | 3        | 7.0              | 1.0                | 7.0
         * 2    | 3        | 7.0              | 1.5849625007211563 | 4.416508275000202
         * 3    | 2        | 3.0              | 2.0                | 1.5
         * 4    | 2        | 3.0              | 2.321928094887362  | 1.2920296742201793
         * 5    | 1        | 1.0              | 2.584962500721156  | 0.38685280723454163
         * 6    | 0        | 0.0              | 2.807354922057604  | 0.0
         *
         * idcg = 14.595390756454922 (sum of last column)
         */
        dcg.setNormalize(true);
        assertEquals(13.84826362927298 / 14.595390756454922, dcg.evaluate("id", hits, rated).getQualityLevel(), 0.00001);
    }

    /**
     * This tests metric when some documents in the search result don't have a rating provided by the user.
     *
     * rank | rel_rank | 2^(rel_rank) - 1 | log_2(rank + 1)    | (2^(rel_rank) - 1) / log_2(rank + 1)
     * -------------------------------------------------------------------------------------------
     * 1    | 3        | 7.0              | 1.0                | 7.0
     * 2    | 2        | 3.0              | 1.5849625007211563 | 1.8927892607143721
     * 3    | 3        | 7.0              | 2.0                | 3.5
     * 4    | n/a      | n/a              | n/a                | n/a
     * 5    | 1        | 1.0              | 2.584962500721156  | 0.38685280723454163
     * 6    | n/a      | n/a              | n/a                | n/a
     *
     * dcg = 12.779642067948913 (sum of last column)
     */
    public void testDCGAtSixMissingRatings() throws IOException, InterruptedException, ExecutionException {
        List<RatedDocument> rated = new ArrayList<>();
        Integer[] relevanceRatings = new Integer[] { 3, 2, 3, null, 1};
        InternalSearchHit[] hits = new InternalSearchHit[6];
        for (int i = 0; i < 6; i++) {
            if (i < relevanceRatings.length) {
                if (relevanceRatings[i] != null) {
                    rated.add(new RatedDocument("index", "type", Integer.toString(i), relevanceRatings[i]));
                }
            }
            hits[i] = new InternalSearchHit(i, Integer.toString(i), new Text("type"), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new ShardId("index", "uuid", 0)));
        }
        DiscountedCumulativeGainAt dcg = new DiscountedCumulativeGainAt(6);
        EvalQueryQuality result = dcg.evaluate("id", hits, rated);
        assertEquals(12.779642067948913, result.getQualityLevel(), 0.00001);
        assertEquals(2, filterUnknownDocuments(result.getHitsAndRatings()).size());

        /**
         * Check with normalization: to get the maximal possible dcg, sort documents by relevance in descending order
         *
         * rank | rel_rank | 2^(rel_rank) - 1 | log_2(rank + 1)    | (2^(rel_rank) - 1) / log_2(rank + 1)
         * -------------------------------------------------------------------------------------------
         * 1    | 3        | 7.0              | 1.0                | 7.0
         * 2    | 3        | 7.0              | 1.5849625007211563 | 4.416508275000202
         * 3    | 2        | 3.0              | 2.0                | 1.5
         * 4    | 1        | 1.0              | 2.321928094887362   | 0.43067655807339
         * 5    | n.a        | n.a              | n.a.  | n.a.
         * 6    | n.a        | n.a              | n.a  | n.a
         *
         * idcg = 13.347184833073591 (sum of last column)
         */
        dcg.setNormalize(true);
        assertEquals(12.779642067948913 / 13.347184833073591, dcg.evaluate("id", hits, rated).getQualityLevel(), 0.00001);
    }

    /**
     * This tests that normalization works as expected when there are more rated documents than search hits
     * because we restrict DCG to be calculated at the fourth position
     *
     * rank | rel_rank | 2^(rel_rank) - 1 | log_2(rank + 1)    | (2^(rel_rank) - 1) / log_2(rank + 1)
     * -------------------------------------------------------------------------------------------
     * 1    | 3        | 7.0              | 1.0                | 7.0
     * 2    | 2        | 3.0              | 1.5849625007211563 | 1.8927892607143721
     * 3    | 3        | 7.0              | 2.0                | 3.5
     * 4    | n/a      | n/a              | n/a                | n/a
     * -----------------------------------------------------------------
     * 5    | 1        | 1.0              | 2.584962500721156  | 0.38685280723454163
     * 6    | n/a      | n/a              | n/a                | n/a
     *
     * dcg = 12.392789260714371 (sum of last column until position 4)
     */
    public void testDCGAtFourMoreRatings() throws IOException, InterruptedException, ExecutionException {
        List<RatedDocument> rated = new ArrayList<>();
        Integer[] relevanceRatings = new Integer[] { 3, 2, 3, null, 1, null};
        InternalSearchHit[] hits = new InternalSearchHit[6];
        for (int i = 0; i < 6; i++) {
            if (i < relevanceRatings.length) {
                if (relevanceRatings[i] != null) {
                    rated.add(new RatedDocument("index", "type", Integer.toString(i), relevanceRatings[i]));
                }
            }
            hits[i] = new InternalSearchHit(i, Integer.toString(i), new Text("type"), Collections.emptyMap());
            hits[i].shard(new SearchShardTarget("testnode", new ShardId("index", "uuid", 0)));
        }
        DiscountedCumulativeGainAt dcg = new DiscountedCumulativeGainAt(4);
        EvalQueryQuality result = dcg.evaluate("id",  Arrays.copyOfRange(hits, 0, 4), rated);
        assertEquals(12.392789260714371 , result.getQualityLevel(), 0.00001);
        assertEquals(1, filterUnknownDocuments(result.getHitsAndRatings()).size());

        /**
         * Check with normalization: to get the maximal possible dcg, sort documents by relevance in descending order
         *
         * rank | rel_rank | 2^(rel_rank) - 1 | log_2(rank + 1)    | (2^(rel_rank) - 1) / log_2(rank + 1)
         * -------------------------------------------------------------------------------------------
         * 1    | 3        | 7.0              | 1.0                | 7.0
         * 2    | 3        | 7.0              | 1.5849625007211563 | 4.416508275000202
         * 3    | 2        | 3.0              | 2.0                | 1.5
         * 4    | 1        | 1.0              | 2.321928094887362   | 0.43067655807339
         * -------------------------------------------------------------------------------------------
         * 5    | n.a        | n.a              | n.a.  | n.a.
         * 6    | n.a        | n.a              | n.a  | n.a
         *
         * idcg = 13.347184833073591 (sum of last column)
         */
        dcg.setNormalize(true);
        assertEquals(12.392789260714371  / 13.347184833073591, dcg.evaluate("id", hits, rated).getQualityLevel(), 0.00001);
    }

    public void testParseFromXContent() throws IOException {
        String xContent = " {\n"
         + "   \"size\": 8,\n"
         + "   \"normalize\": true\n"
         + "}";
        XContentParser parser = XContentFactory.xContent(xContent).createParser(xContent);
        DiscountedCumulativeGainAt dcgAt = DiscountedCumulativeGainAt.fromXContent(parser, () -> ParseFieldMatcher.STRICT);
        assertEquals(8, dcgAt.getPosition());
        assertEquals(true, dcgAt.getNormalize());
    }

    public static DiscountedCumulativeGainAt createTestItem() {
        int position = randomIntBetween(0, 1000);
        boolean normalize = randomBoolean();
        Integer unknownDocRating = new Integer(randomIntBetween(0, 1000));

        return new DiscountedCumulativeGainAt(position, normalize, unknownDocRating);
    }
    public void testXContentRoundtrip() throws IOException {
        DiscountedCumulativeGainAt testItem = createTestItem();
        XContentParser itemParser = RankEvalTestHelper.roundtrip(testItem);
        itemParser.nextToken();
        itemParser.nextToken();
        DiscountedCumulativeGainAt parsedItem = DiscountedCumulativeGainAt.fromXContent(itemParser, () -> ParseFieldMatcher.STRICT);
        assertNotSame(testItem, parsedItem);
        assertEquals(testItem, parsedItem);
        assertEquals(testItem.hashCode(), parsedItem.hashCode());
    }
}
