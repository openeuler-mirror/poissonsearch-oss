Smart Chinese Analysis for Elasticsearch
==================================

The Smart Chinese Analysis plugin integrates Lucene Smart Chinese analysis module into elasticsearch.

In order to install the plugin, simply run: 

```sh
bin/plugin -install elasticsearch/elasticsearch-analysis-smartcn/2.3.0
```


| elasticsearch |  Smart Chinese Analysis Plugin  |   Docs     |  
|---------------|-----------------------|------------|
| master        |  Build from source    | See below  |
| es-1.x        |  Build from source    | [2.5.0-SNAPSHOT](https://github.com/elasticsearch/elasticsearch-analysis-smartcn/tree/es-1.x/#version-250-snapshot-for-elasticsearch-1x)  |
| es-1.4        |  Build from source    | [2.4.0-SNAPSHOT](https://github.com/elasticsearch/elasticsearch-analysis-smartcn/tree/es-1.4/#version-240-snapshot-for-elasticsearch-1x)  |
| es-1.3        |  2.3.0                | [2.3.0](https://github.com/elasticsearch/elasticsearch-analysis-smartcn/tree/v2.3.0/#smart-chinese-analysis-for-elasticsearch)  |
| es-1.2        |  2.2.0                | [2.2.0](https://github.com/elasticsearch/elasticsearch-analysis-smartcn/tree/v2.2.0/#smart-chinese-analysis-for-elasticsearch)  |
| es-1.1        |  2.1.0                | [2.1.0](https://github.com/elasticsearch/elasticsearch-analysis-smartcn/tree/v2.1.0/#smart-chinese-analysis-for-elasticsearch)  |
| es-1.0        |  2.0.0                | [2.0.0](https://github.com/elasticsearch/elasticsearch-analysis-smartcn/tree/v2.0.0/#smart-chinese-analysis-for-elasticsearch)  |
| es-0.90       |  1.8.0                | [1.8.0](https://github.com/elasticsearch/elasticsearch-analysis-smartcn/tree/v1.8.0/#smart-chinese-analysis-for-elasticsearch)  |

To build a `SNAPSHOT` version, you need to build it with Maven:

```bash
mvn clean install
plugin --install analysis-smartcn \
       --url file:target/releases/elasticsearch-analysis-smartcn-X.X.X-SNAPSHOT.zip
```

## User guide
>>>>>>> bb823d1... Docs: make the welcome page more obvious

The plugin includes the `smartcn` analyzer and `smartcn_tokenizer` tokenizer.

 Note that `smartcn_word` token filter and `smartcn_sentence` have been deprecated.

License
-------

    This software is licensed under the Apache 2 license, quoted below.

    Copyright 2009-2014 Elasticsearch <http://www.elasticsearch.org>

    Licensed under the Apache License, Version 2.0 (the "License"); you may not
    use this file except in compliance with the License. You may obtain a copy of
    the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
    License for the specific language governing permissions and limitations under
    the License.
