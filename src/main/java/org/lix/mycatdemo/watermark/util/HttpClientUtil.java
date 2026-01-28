package org.lix.mycatdemo.watermark.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Slf4j
public class HttpClientUtil {

    private static HttpClient getClient() throws NoSuchAlgorithmException,
            KeyStoreException, KeyManagementException {
        SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null,
                (arg0, arg1) -> true).build();
        SSLConnectionSocketFactory sslConnectionSocketFactory = new
                SSLConnectionSocketFactory(sslContext);
        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(600 * 1000)
                .setConnectTimeout(600 * 1000)
                .setConnectionRequestTimeout(600 * 1000)
                .build();
        return HttpClients.custom().setDefaultRequestConfig(requestConfig).setSSLSocketFactory(sslConnectionSocketFactory).build();
    }


    public static InputStream getAndGetInputStream(String url) throws Exception {
        HttpClient client = getClient();
        //checkWhiteList(url);
        HttpGet httpGet = new HttpGet(url);
        HttpResponse response = client.execute(httpGet);
        return response.getEntity().getContent();
    }
}
