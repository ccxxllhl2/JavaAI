package com.jackal.group.tfx.gau.util
import io.micrometer.common.util.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HttpUtil {
    private static final TrustManager DUMMY_TRUST_MANAGER = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {

        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {

        }

        @Override
        public X509Certificate[] getAcceptedIssuers() { 
            return new X509Certificate[0]; 
        }
        
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SocketFactory factory) throws CertificateException {

        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SocketFactory factory) throws CertificateException {

        }
    };
    public static HttpClient defaultClient(boolean redirect) {
        try {
            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { DUMMY_TRUST_MANAGER }, new SecureRandom());

            var builder = HttpClient.newBuilder().sslContext(sslContext);
            var proxyOpt = detectProxy();
            if (proxyOpt.isPresent()) {
                var proxyInfo = proxyOpt.get();
                builder = builder.proxy(ProxySelector.of(new InetSocketAddress(proxyInfo.getHost(), proxyInfo.getPort())));
            }
            if (redirect) {
                return builder.followRedirects(HttpClient.Redirect.NORMAL).build();
            } else {
                return builder.build();
            }
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static final boolean is2xxSuccessful(final HttpResponse<?> resp) {
        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }

    public static Optional<ProxyInfo> detectProxy() {
        var proxy = System.getenv("HTTPS_PROXY");
        if (StringUtils.isBlank(httpProxy)) {
            httpProxy = System.getenv("http_proxy");
            if (StringUtils.isBlank(httpProxy)) {
                httpProxy = System.getenv("HTTPS_PROXY");
                if (StringUtils.isBlank(httpProxy)) {
                    httpProxy = System.getenv("http_proxy");
                }
            }
        }
        if (StringUtils.isBlank(httpProxy)) return Optional.empty();
        return ProxyInfo.fromString(httpProxy);
    }

    public static String formDataAsString(Map<String, String> formData) {
        StringBuilder formBodyBuilder = new StringBuilder();
        for (Map.Entry<String, String> singleEntry : formData.entrySet()) {
            if (formBodyBuilder.length() > 0) {
                formBodyBuilder.append("&");
            }
            formBodyBuilder.append(URLEncoder.encode(singleEntry.getKey(), StandardCharsets.UTF_8));
            formBodyBuilder.append("=");
            formBodyBuilder.append(URLEncoder.encode(singleEntry.getValue(), StandardCharsets.UTF_8));
        }
        return formBodyBuilder.toString();
    }

    public static HttpResponse<String> getAsString(String url, Map<String, String> headers) throws IOException, InterruptedException {
        var requestBuilder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        if (headers!=null) headers.forEach((k,v) -> requestBuilder.header(k,v));

        return defaultClient(false).send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static HttpResponse<InputStream> getAsInputStream(String url, Map<String, String> headers) throws IOException, InterruptedException {
        var requestBuilder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        if (headers!=null) headers.forEach((k,v) -> requestBuilder.header(k,v));

        return defaultClient(false).send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    public static HttpResponse<String> post(Object body, Map<String, String> headers，String url) throws IOException, InterruptedException {
        var requestBuilder = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(JacksonUtils.toJson(body)));
        if (headers!=null)headers.forEach((k,v) -> postBuilder.header(k,v));
        return defaultClient(false).send(postBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static HttpResponse<String> postUrlEncodedForm(HttpClient client, Map<String, String> parameters, String url) throws IOException, InterruptedException {
        //
        String form = parameters.entrySet()
            .stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", String.valueOf(MediaType.APPLICATION_FORM_URLENCODED))
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ProxyInfo {
        private String host;
        private int port;
        private String user;
        private String pass;
        //
        //
        //
        //
        private static Pattern pattern = Pattern.compile("https?://((?<user>[^:]+):(?<pass>[^:@]+)@)?(?<host>[^:@]+)+:(?<port>\\d+)");
        public static Optional<ProxyInfo> fromString(String proxy) {
            var m = pattern.matcher(proxy);
            if (m.find()) {
                var o = new ProxyInfo();
                o.setHost(m.group("host"));
                o.setPort(Integer.valueOf(m.group("port")));
                if (m.group("user") != null) {
                    o.setUser(m.group("user"));
                    o.setPass(m.group("pass"));
                }

                return Optional.of(o);
            }
            return Optional.empty();
        }
    }

}
