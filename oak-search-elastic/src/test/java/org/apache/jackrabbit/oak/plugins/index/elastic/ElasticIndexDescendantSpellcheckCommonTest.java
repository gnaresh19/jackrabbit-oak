/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.elastic;

import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.index.IndexDescendantSpellcheckCommonTest;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import javax.jcr.Repository;

public class ElasticIndexDescendantSpellcheckCommonTest extends IndexDescendantSpellcheckCommonTest {

    @ClassRule
    public static final ElasticConnectionRule elasticRule =
            new ElasticConnectionRule(ElasticTestUtils.ELASTIC_CONNECTION_STRING);

    @Override
    protected Repository createJcrRepository() {
        indexOptions = new ElasticIndexOptions();
        repositoryOptionsUtil = new ElasticTestRepositoryBuilder(elasticRule).build();
        Oak oak = repositoryOptionsUtil.getOak();
        Jcr jcr = new Jcr(oak);
        return jcr.createRepository();
    }

    //TODO this seems to be similar to
    //https://discuss.elastic.co/t/elasticsearch-suggestion-completes-return-incorrect-number-of-suggestions/214522
    //Even increasing the suggester query size the number of max results seems to be fixed to 5
    @Test
    @Ignore("OAK-9857")
    @Override
    public void noDescendantSuggestsAll() {
        super.noDescendantSuggestsAll();
    }

    //TODO If path restriction is not enabled, all suggestions should be returned see #noDescendantSuggestsAll
    @Test
    @Ignore("OAK-3994")
    @Override
    public void descendantSuggestionRequirePathRestrictionIndex() throws Exception {
        super.descendantSuggestionRequirePathRestrictionIndex();
    }
}
