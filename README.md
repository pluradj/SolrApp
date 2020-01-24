# SolrApp

## Prerequisites

* [Java Development Kit 8](https://adoptopenjdk.net/)
* [Apache Maven 3](https://maven.apache.org/)

## Build

```
mvn clean install
```

## Configure

* `conf/solrapp.properties`

```
# zookeeper server
zookeeper.url=localhost:2181
# name of the Solr collection
index.name=mixedIndex
# filter query (fq)
filter.query=godtype_t:/[Dd][Ee][Mm][Ii][Gg][Oo][Dd]/
# start index (default 0)
start.index=0
# maximum rows per query (default 50)
max.rows=50
# total limit (default -1 for unlimited)
total.limit=-1
# whether to apply a default sort by id/uniqueKey (default false)
sort.by.id=false
```

## Run

```
mvn exec:java
```
