package org.lix.mycatdemo.config;


import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ElasticConfig {

    private static final RestClient restClient;
    private static final ElasticsearchTransport elasticsearchTransport;

    static {
        // XXX 配置ES集群
        restClient = RestClient.builder(
                new HttpHost("localhost", 9200),
                new HttpHost("localhost", 9201)
        ).build();
        elasticsearchTransport = new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    /**
     * XXX 阻塞式客户端
     * 关闭客户端同时关闭传输层对象
     * @return ElasticsearchClient 阻塞式客户端
     */
    @Bean(destroyMethod = "close")
    public ElasticsearchClient elasticsearchClient() {
        return new ElasticsearchClient(elasticsearchTransport);
    }

    /**
     * XXX 异步式客户端
     * @return 异步式客户端
     */
    @Bean(destroyMethod = "close")
    public ElasticsearchAsyncClient elasticsearchAsyncClient() {
        return new ElasticsearchAsyncClient(elasticsearchTransport);
    }


}
