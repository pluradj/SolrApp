package org.example;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.StreamingResponseCallback;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CommonParams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

public class SolrApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(SolrApp.class);

    final Properties props;

    public SolrApp(String fileName) throws IOException {
        props = new Properties();
        if (fileName == null) {
            fileName = "conf/solrapp.properties";
        }
        props.load(new FileReader(fileName));
        LOGGER.info(props.toString());
    }

    public void runStandaloneQuery() {
        final String zookeeperUrl = props.getProperty("zookeeper.url");
        final CloudSolrClient.Builder builder = new CloudSolrClient.Builder().
                withZkHost(zookeeperUrl);
        final CloudSolrClient solrClient = builder.build();
        solrClient.connect();

        final String collection = props.getProperty("index.name");

        Integer totalLimit = Integer.parseInt(props.getProperty("total.limit"));
        if (totalLimit < 0) totalLimit = null;
        final int startIndex = Integer.parseInt(props.getProperty("start.index"));
        final int maxResultSetSize = Integer.parseInt(props.getProperty("max.rows"));
        final SolrQuery solrQuery = new SolrQuery("*:*");
        solrQuery.set(CommonParams.FL, "id");
        solrQuery.addFilterQuery(props.getProperty("filter.query"));
        solrQuery.setStart(startIndex);
        solrQuery.setRows(maxResultSetSize);
        if (Boolean.parseBoolean(props.getProperty("sort.by.id"))) {
            solrQuery.addSort(new SolrQuery.SortClause("id", SolrQuery.ORDER.asc));
        }

        final Function<SolrDocument, String> function = (SolrDocument doc) -> { return doc.getFieldValue("id").toString(); };

        try {
            SolrResultIterator iter = new SolrResultIterator(solrClient, totalLimit, 0, maxResultSetSize, collection, solrQuery, function);
            ArrayList list = new ArrayList();
            TreeSet set = new TreeSet();
            while (iter.hasNext()) {
                Object o = iter.next();
                list.add(o);
                set.add(o);
            }
            LOGGER.info(String.format("list=%s, set=%s", list.size(), set.size()));
            LOGGER.debug(String.format("stream:  %s", list));
            Collections.sort(list);
            LOGGER.debug(String.format("sorted:  %s", list));
            LOGGER.debug(String.format("treeset:  %s", set));
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        } finally {
            try {
                solrClient.close();
            } catch (IOException e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    private class SolrResultIterator<E> implements Iterator<E> {
        private final SolrClient solrClient;
        private final BlockingQueue<E> queue;
        private int count;
        private int numBatches;
        private final Long limit;
        private final int offset;
        private final int batchSize;
        private final String collection;
        private final SolrQuery solrQuery;
        private final Function<SolrDocument, E> getFieldValue;
        private final SolrCallbackHandler handler;

        public SolrResultIterator(SolrClient solrClient, Integer limit, int offset, int nbDocByQuery,
                                    String collection, SolrQuery solrQuery, Function<SolrDocument, E> function)
                throws SolrServerException, IOException {
            this.solrClient = solrClient;
            this.count = 0;
            this.offset = offset;
            this.batchSize = nbDocByQuery;
            this.queue = new LinkedBlockingQueue<>();
            this.collection = collection;
            this.solrQuery = solrQuery;
            this.getFieldValue = function;
            this.handler = new SolrCallbackHandler(this, this.getFieldValue);
            LOGGER.trace(String.format("() queryAndStreamResponse count=%s, batchSize=%s, limit=%s, numBatches=%s", count, batchSize, limit, 1));
            QueryResponse queryResponse = solrClient.queryAndStreamResponse(this.collection, this.solrQuery, this.handler);
            SolrDocumentList solrDocumentList = queryResponse.getResults();
            final long nbFound = solrDocumentList.getNumFound() - offset;
            this.limit = limit != null ? Math.min(nbFound, limit) : nbFound;
            numBatches = 1;
        }

        public BlockingQueue<E> getQueue() {
            return queue;
        }

        @Override
        public boolean hasNext() {
            if (count != 0 && count % batchSize == 0 && count < limit) {
                // need to request the next batch of results
                try {
                    final int nextStart = numBatches * batchSize + offset;
                    solrQuery.setStart(nextStart);
                    LOGGER.trace(String.format("hasNext() queryAndStreamResponse count=%s, batchSize=%s, limit=%s, nextStart=%s, numBatches=%s", count, batchSize, limit, nextStart, (numBatches+1)));
                    solrClient.queryAndStreamResponse(collection, solrQuery, handler);
                    numBatches++;
                } catch (final SolrServerException e) {
                    throw new UncheckedSolrException(e.getMessage(), e);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e.getMessage(), e);
                }
            }
            LOGGER.trace(String.format("hasNext() %s, count=%s, limit=%s", (count<limit), count, limit));
            return count < limit;
        }

        @Override
        public E next() {
            try {
                count++;
                final E e = queue.take();
                LOGGER.trace(String.format("next() %s", e));
                return e;
            } catch (final InterruptedException e) {
                throw new UncheckedIOException(new IOException("Interrupted waiting on queue", e));
            }
        }
    }

    private class SolrCallbackHandler<E> extends StreamingResponseCallback {
        private final SolrResultIterator<E> iterator;
        private final Function<SolrDocument, E> function;

        public SolrCallbackHandler(SolrResultIterator<E> iterator, Function<SolrDocument, E> function) {
            this.function = function;
            this.iterator = iterator;
        }

        @Override
        public void streamDocListInfo(long nbFound, long start, Float aMaxScore) {
            LOGGER.trace(String.format("streamDocListInfo: nbFound=%s, start=%s, aMaxScore%s", nbFound, start, aMaxScore));
        }

        @Override
        public void streamSolrDocument(SolrDocument doc) {
            LOGGER.trace(String.format("addDocument: %s", function.apply(doc)));
            iterator.getQueue().add(function.apply(doc));
        }
    }

    private class UncheckedSolrException extends RuntimeException {
        public UncheckedSolrException(String msg, SolrServerException cause) {
            super(msg, cause);
        }
    }

    public static void main(String[] args) {
        final String fileName = (args != null && args.length > 0) ? args[0] : null;
        try {
            final SolrApp app = new SolrApp(fileName);
            app.runStandaloneQuery();
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }
}
