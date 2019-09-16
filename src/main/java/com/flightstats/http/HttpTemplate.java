package com.flightstats.http;

import com.flightstats.util.Part;
import com.flightstats.util.UUIDGenerator;
import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.Retryer;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.flightstats.http.HttpException.Details;
import static com.google.common.base.Charsets.UTF_8;
import static java.util.stream.Collectors.toList;

public class HttpTemplate {
    public static final ContentType MULTIPART_MIXED = ContentType.create("multipart/mixed", UTF_8);
    public static final String APPLICATION_JSON = "application/json";
    public static final Logger logger = LoggerFactory.getLogger(HttpTemplate.class);

    private final HttpClient client;
    private final UUIDGenerator uuidGenerator;
    private final Optional<Gson> gson;
    private final Retryer<Response> retryer;
    private final String defaultContentType;
    private final String acceptType;

    public HttpTemplate(HttpClient client, Retryer<Response> retryer, String contentType, String acceptType) {
        this.client = client;
        this.gson = Optional.empty();
        this.retryer = retryer;
        this.defaultContentType = contentType;
        this.acceptType = acceptType;
        this.uuidGenerator = new UUIDGenerator();
    }

    @Inject
    public HttpTemplate(HttpClient client, Gson gson, Retryer<Response> retryer, UUIDGenerator uuidGenerator) {
        this.client = client;
        this.uuidGenerator = uuidGenerator;
        this.gson = Optional.ofNullable(gson);
        this.retryer = retryer;
        this.defaultContentType = APPLICATION_JSON;
        this.acceptType = APPLICATION_JSON;
    }

    public <T> T get(String hostUrl, String path, Function<String, T> responseCreator, NameValuePair... queryParams) {
        URI uri;
        try {
            uri = new URIBuilder(hostUrl).setPath(path).setParameters(queryParams).build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return get(uri, responseCreator);
    }

    public <T> T get(String uri, Function<String, T> responseCreator) {
        return get(URI.create(uri), responseCreator);
    }

    public <T> T get(URI uri, Function<String, T> responseCreator) {
        AtomicReference<T> result = new AtomicReference<>();
        get(uri, (Response response) -> {
            if (isFailedStatusCode(response.getCode())) {
                throw new HttpException(new Details(response.getCode(), "Get failed to: " + uri + ". response: " + response));
            }
            result.set(responseCreator.apply(response.getBodyString(UTF_8)));
        });
        return result.get();
    }

    public int get(String uri, Consumer<Response> responseConsumer) {
        return get(URI.create(uri), responseConsumer).getCode();
    }

    public Response get(URI uri) {
        return get(uri, (Response c) -> {
        });
    }

    public Response get(URI uri, Consumer<Response> responseConsumer) {
        return get(uri, responseConsumer, Collections.emptyMap());
    }

    public Response get(URI uri, Consumer<Response> responseConsumer, Map<String, String> extraHeaders) {
        HttpGet request = new HttpGet(uri);
        addExtraHeaders(request, extraHeaders);
        return handleRequest(request, responseConsumer);
    }

    public Response get(URI uri, Map<String, String> extraHeaders) {
        return get(uri, res -> {
        }, extraHeaders);
    }

    private void addExtraHeaders(HttpRequestBase request, Map<String, String> extraHeaders) {
        extraHeaders.entrySet()
                .stream()
                .filter(e -> !e.getKey().equalsIgnoreCase("Content-Type"))
                .forEach(entry -> request.setHeader(entry.getKey(), entry.getValue()));
    }

    public Response head(URI uri) {
        return head(uri, true);
    }

    public Response head(String uri) {
        return head(uri, true);
    }

    public Response head(String uri, boolean followRedirects) {
        return head(URI.create(uri), followRedirects);
    }

    public Response head(URI uri, boolean followRedirects) {
        HttpHead httpHead = new HttpHead(uri);
        if (!followRedirects) {
            httpHead.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
        }
        return handleRequest(httpHead, x -> {
        });
    }

    public <T> T get(URI uri, Type type) {
        if (!gson.isPresent()) {
            throw new IllegalStateException("Must provide gson for deserializion");
        }
        return get(uri, (String body) -> gson.get().fromJson(body, type));
    }

    public String getSimple(String uri) {
        return get(uri, (Function<String, String>) s -> s);
    }

    private Response handleRequest(HttpRequestBase request, Consumer<Response> responseConsumer) {
        request.setHeader("Accept", acceptType);
        try {
            Response response = retryer.call(() -> convertHttpResponse(client.execute(request)));
            responseConsumer.accept(response);
            return response;
        } catch (ExecutionException | RetryException e) {
            throw new RuntimeException(e);
        } finally {
            request.reset();
        }
    }

    private Response convertHttpResponse(HttpResponse httpResponse) throws IOException {
        int statusCode = httpResponse.getStatusLine().getStatusCode();
        byte[] body = new byte[0];
        HttpEntity entity = httpResponse.getEntity();
        if (entity != null) {
            InputStream content = entity.getContent();
            body = ByteStreams.toByteArray(content);
        }
        return new Response(statusCode, body, mapHeaders(httpResponse));
    }

    private Multimap<String, String> mapHeaders(HttpResponse response) {
        Header[] headers = response.getAllHeaders();
        ListMultimap<String, Header> headersByName = Multimaps.index(Arrays.asList(headers), Header::getName);
        return Multimaps.transformValues(headersByName, Header::getValue);
    }

    public int postWithNoResponseCodeValidation(String fullUri, Object bodyToPost, Consumer<Response> responseConsumer) {
        String bodyEntity = convertBodyToString(bodyToPost);
        Response response = executePost(fullUri, responseConsumer, defaultContentType, new StringEntity(bodyEntity, UTF_8));
        return response.getCode();
    }

    private Response executePost(String fullUri, String contentType, HttpEntity entity) throws Exception {
        return executePost(fullUri, contentType, entity, Collections.emptyMap());
    }

    private Response executePost(String fullUri, String contentType, HttpEntity entity, Map<String, String> extraHeaders) throws Exception {
        return executePost(fullUri, x -> {
        }, contentType, entity, extraHeaders);
    }

    private Response executePost(String fullUri, Consumer<Response> responseConsumer, String contentType, HttpEntity entity) {
        return executePost(fullUri, responseConsumer, contentType, entity, Collections.emptyMap());
    }

    private Response executePost(String fullUri, Consumer<Response> responseConsumer, String contentType, HttpEntity entity, Map<String, String> extraHeaders) {
        HttpPost httpPost = new HttpPost(fullUri);
        addExtraHeaders(httpPost, extraHeaders);
        return execute(httpPost, responseConsumer, contentType, entity);
    }

    private Response executePut(String fullUri, Consumer<Response> responseConsumer, String contentType, HttpEntity entity) throws Exception {
        HttpPut httpPut = new HttpPut(fullUri);
        return execute(httpPut, responseConsumer, contentType, entity);
    }

    private Response execute(HttpEntityEnclosingRequestBase httpRequest, String contentType, HttpEntity entity) throws IOException {
        return execute(httpRequest, x -> {
        }, contentType, entity);
    }

    private Response execute(HttpEntityEnclosingRequestBase httpRequest, Consumer<Response> responseConsumer, String contentType, HttpEntity entity) {
        try {
            return retryer.call(() -> {
                try {
                    httpRequest.setHeader("Content-Type", contentType);
                    httpRequest.setHeader("Accept", acceptType);
                    httpRequest.setEntity(entity);
                    HttpResponse httpResponse = client.execute(httpRequest);
                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    byte[] body = ByteStreams.toByteArray(httpResponse.getEntity().getContent());
                    if (isRetryableStatusCode(statusCode)) {
                        logger.error("Internal server error, status code " + statusCode);
                        throw new HttpException(new Details(statusCode, httpRequest.getMethod() + " failed to: " + httpRequest.getURI() + ".  Status = " + statusCode + ", message = " + new String(body)));
                    }
                    Response response = new Response(statusCode, body, mapHeaders(httpResponse));
                    responseConsumer.accept(response);
                    return response;
                } finally {
                    httpRequest.reset();
                }
            });
        } catch (ExecutionException | RetryException e) {
            throw new RuntimeException(e);
        }
    }


    private boolean isFailedStatusCode(int responseStatusCode) {
        return (HttpStatus.SC_OK > responseStatusCode) || (responseStatusCode > HttpStatus.SC_NO_CONTENT);
    }

    private boolean isRetryableStatusCode(int responseStatusCode) {
        return 502 <= responseStatusCode && responseStatusCode <= 504;
    }

    private String convertBodyToString(Object bodyToPost) {
        if (gson.isPresent()) {
            return gson.get().toJson(bodyToPost);
        } else {
            return String.valueOf(bodyToPost);
        }
    }

    public Response post(URI uri, byte[] bytes, String contentType) {
        return post(uri, bytes, contentType, new HashMap<>());
    }

    public Response post(URI uri, byte[] bytes, String contentType, Map<String, String> extraHeaders) {
        HashMap<String, String> headers = new HashMap<>(extraHeaders);
        headers.put("Content-Type", contentType);
        return post(uri, bytes, headers);
    }

    public Response post(URI uri, byte[] bytes, Map<String, String> extraHeaders) {
        try {
            return executePost(uri.toString(), extraHeaders.getOrDefault("Content-Type", defaultContentType), new ByteArrayEntity(bytes), extraHeaders);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Fire &amp; Forget...don't care about the response at all.
     */
    public void post(String fullUri, Object bodyToPost) {
        postWithResponse(fullUri, bodyToPost, httpResponse -> {
        });
    }

    /**
     * Post, and return the response body as a String.
     */
    public String postSimple(String fullUri, Object bodyToPost) {
        return post(fullUri, bodyToPost, s -> s);
    }

    public Response postMultipart(String uri, List<Part> parts) {
        return postMultipart(uri, parts, Optional.<String>empty());
    }

    public Response postMultipart(String uri, List<Part> parts, Optional<String> separator) {
        return postMultipart(uri, parts, separator, Optional.empty());
    }

    public Response postMultipart(String uri, List<Part> parts, Optional<String> separator, Optional<ContentType> contentType) {
        try {
            HttpEntity multipartEntity = buildMultipartEntity(parts, separator, contentType);
            return executePost(uri, multipartEntity.getContentType().getValue(), multipartEntity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T post(String fullUri, Object bodyToPost, Function<String, T> responseConverter) {
        AtomicReference<T> result = new AtomicReference<>();
        postWithResponse(fullUri, bodyToPost, (Response response) -> result.set(responseConverter.apply(response.getBodyString(UTF_8))));
        return result.get();
    }

    public Response put(URI uri, byte[] bytes, String contentType) {
        try {
            HttpPut httpPut = new HttpPut(uri.toString());
            return execute(httpPut, contentType, new ByteArrayEntity(bytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Response put(URI uri, Object body) {
        return put(uri, convertBodyToString(body).getBytes(UTF_8), defaultContentType);
    }

    /**
     * todo: this needs a better name, but different than "post", so it doesn't collide with the other one. I hate type erasure!
     */
    public Response postWithResponse(String fullUri, Object bodyToPost, Consumer<Response> responseConsumer) {
        String bodyEntity = convertBodyToString(bodyToPost);
        Response response = executePost(fullUri, responseConsumer, defaultContentType, new StringEntity(bodyEntity, UTF_8));
        if (isFailedStatusCode(response.getCode())) {
            throw new HttpException(new Details(response.getCode(), "Post failed to: " + fullUri + ". response: " + response));
        }
        return response;
    }

    /**
     * Note: does not use the Retryer.  Todo: change it so that it does.
     */
    public int postSimpleHtmlForm(String fullUri, Map<String, String> formValues) throws Exception {
        List<NameValuePair> nameValuePairs = formValues.entrySet().stream()
                .map(entry -> new BasicNameValuePair(entry.getKey(), entry.getValue()))
                .collect(toList());
        HttpPost httpPost = new HttpPost(fullUri);
        try {
            httpPost.setEntity(new UrlEncodedFormEntity(nameValuePairs));
            HttpResponse response = client.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();
            if (isFailedStatusCode(statusCode)) {
                throw new HttpException(new Details(statusCode, "Post failed to: " + fullUri));
            }
            return statusCode;
        } finally {
            httpPost.reset();
        }
    }

    public Response delete(URI uri) {
        try {
            return retryer.call(() -> {
                HttpDelete delete = new HttpDelete(uri);
                try {
                    try {
                        HttpResponse response = client.execute(delete);
                        return convertHttpResponse(response);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Error issuing DELETE against " + uri, e);
                    }
                } finally {
                    delete.reset();
                }
            });
        } catch (ExecutionException | RetryException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpEntity buildMultipartEntity(List<Part> parts, Optional<String> separator, Optional<ContentType> contentType) {
        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        //If we dont set the content type, the lib will set the default.
        if (contentType.isPresent()) {
            entityBuilder.setContentType(contentType.get());
        }
        entityBuilder.setBoundary(separator.orElse("fava_" + uuidGenerator.generateUUID()));

        parts.forEach(part -> entityBuilder.addTextBody(part.getName(), part.getContent(), ContentType.parse(part.getContentType())));

        return entityBuilder.build();
    }
}
