package org.lix.mycatdemo.es.test;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Slf4j
@Service
public class FirstService {

    @Resource
    private ElasticsearchClient esClient;

    @PostConstruct
    public void init(){
        test();
    }

    public void test(){


    }
}
