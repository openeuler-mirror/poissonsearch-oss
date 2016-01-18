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

package org.elasticsearch.search.suggest.phrase;

import org.elasticsearch.search.suggest.phrase.PhraseSuggestionBuilder.LinearInterpolation;

public class LinearInterpolationModelTests extends SmoothingModelTest<LinearInterpolation> {

    @Override
    protected LinearInterpolation createTestModel() {
        double trigramLambda = randomDoubleBetween(0.0, 10.0, false);
        double bigramLambda = randomDoubleBetween(0.0, 10.0, false);
        double unigramLambda = randomDoubleBetween(0.0, 10.0, false);
        // normalize
        double sum = trigramLambda + bigramLambda + unigramLambda;
        return new LinearInterpolation(trigramLambda / sum, bigramLambda / sum, unigramLambda / sum);
    }

    /**
     * mutate the given model so the returned smoothing model is different
     */
    @Override
    protected LinearInterpolation createMutation(LinearInterpolation original) {
        // swap two values permute original lambda values
        switch (randomIntBetween(0, 2)) {
        case 0:
            // swap first two
            return new LinearInterpolation(original.getBigramLambda(), original.getTrigramLambda(), original.getUnigramLambda());
        case 1:
            // swap last two
            return new LinearInterpolation(original.getTrigramLambda(), original.getUnigramLambda(), original.getBigramLambda());
        case 2:
        default:
            // swap first and last
            return new LinearInterpolation(original.getUnigramLambda(), original.getBigramLambda(), original.getTrigramLambda());
        }
    }
}
