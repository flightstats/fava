package com.flightstats.http;


import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.github.rholder.retry.WaitStrategies;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Collections.EMPTY_MAP;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class HttpTemplateTest {
    @Test(expected = IllegalStateException.class)
    public void testGsonRequiresCorrectContentType() throws Exception {
        final HttpTemplate httpTemplate = new HttpTemplate(mock(HttpClient.class), null, "*/*", "*/*");
        httpTemplate.get(URI.create("foo"), String.class);
    }

    @Test
    public void testNonDefaultPostContentTypeHeader() throws Exception {
        // given
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity entity = new StringEntity("bar", "UTF-8");

        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(httpResponse.getEntity()).thenReturn(entity);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[] { new BasicHeader("Content-Type", "text/plain") });

        AtomicReference<HttpPost> post = new AtomicReference<>();

        when(httpClient.execute(any(HttpPost.class)))
                .thenAnswer( (a) -> {
                    post.set((HttpPost) a.getArguments()[0]);
                    return httpResponse;
                });

        final HttpTemplate httpTemplate = new HttpTemplate(httpClient, null, "application/json", "application/json");
        httpTemplate.post(URI.create("foo"), "foo".getBytes(), "*/*");

        final Header[] contentType = post.get().getHeaders("Content-Type");
        assertEquals(1, contentType.length);
        assertEquals("Content-Type: */*", contentType[0].toString());
    }


    @Test
    public void testPostWithResponse_successOnSingleAttempt() throws Exception {
        // given
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class, RETURNS_DEEP_STUBS);
        StatusLine statusLine = mock(StatusLine.class);

        when(httpClient.execute(any(HttpPost.class))).thenReturn(httpResponse);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[0]);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(httpResponse.getEntity().getContent()).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(statusLine.getStatusCode()).thenReturn(201);

        Gson gson = new GsonBuilder().create();
        HttpTemplate httpTemplate = new HttpTemplate(httpClient, gson, buildRetryer());

        // when
        Response result = httpTemplate.postWithResponse("/some/uri", new Object(), mock(Consumer.class));

        // then
        assertEquals(201, result.getCode());
        verify(httpClient).execute(any(HttpPost.class));
    }

    private Retryer<Response> buildRetryer() {
        return RetryerBuilder.<Response>newBuilder()
                .retryIfExceptionOfType(HttpException.class)
                .withStopStrategy(StopStrategies.stopAfterAttempt(5))
                .withWaitStrategy(WaitStrategies.fixedWait(10, TimeUnit.MILLISECONDS))
                .build();
    }

    @Test
    public void testPostWithResponse_successfulRetryAfterSomeErrors() throws Exception {
        // given
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class, RETURNS_DEEP_STUBS);
        StatusLine statusLine = mock(StatusLine.class);

        when(httpClient.execute(any(HttpPost.class))).thenReturn(httpResponse);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[0]);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(httpResponse.getEntity().getContent()).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        when(statusLine.getStatusCode()).thenReturn(502).thenReturn(503).thenReturn(201);

        Gson gson = new GsonBuilder().create();
        HttpTemplate httpTemplate = new HttpTemplate(httpClient, gson, buildRetryer());

        // when
        Response result = httpTemplate.postWithResponse("/some/uri", new Object(), mock(Consumer.class));

        // then
        assertEquals(201, result.getCode());
        verify(httpClient, times(3)).execute(any(HttpPost.class));
    }

    @Test(expected = HttpException.class)
    public void testPostWithResponse_unsuccessful400WithNoRetrying() throws Exception {
        // given
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);

        when(httpClient.execute(any(HttpPost.class))).thenReturn(httpResponse);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(400);

        HttpEntity httpEntity = mock(HttpEntity.class);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[0]);
        when(httpResponse.getEntity()).thenReturn(httpEntity);
        when(httpEntity.getContent()).thenReturn(new ByteArrayInputStream("some output".getBytes("UTF-8")));

        Gson gson = new GsonBuilder().create();
        HttpTemplate httpTemplate = new HttpTemplate(httpClient, gson, buildRetryer());

        // when
        httpTemplate.postWithResponse("/some_uri", new Object(), mock(Consumer.class));

        // then exception
    }

    @Test(expected = HttpException.class)
    public void testGetWithResponse_failedAsException() throws Exception {
        // given
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);

        when(httpClient.execute(any(HttpGet.class))).thenReturn(httpResponse);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[0]);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(400);

        HttpEntity httpEntity = mock(HttpEntity.class);
        when(httpResponse.getEntity()).thenReturn(httpEntity);
        when(httpEntity.getContent()).thenReturn(new ByteArrayInputStream("some output".getBytes("UTF-8")));

        Gson gson = new GsonBuilder().create();
        HttpTemplate httpTemplate = new HttpTemplate(httpClient, gson, null);

        // when/then
        httpTemplate.get("/some_uri", (String s) -> null);
    }

    @Test
    public void testGetWithResponse_failedStatusCode() throws Exception {
        // given
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);

        when(httpClient.execute(any(HttpGet.class))).thenReturn(httpResponse);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[0]);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(400);

        HttpEntity httpEntity = mock(HttpEntity.class);
        when(httpResponse.getEntity()).thenReturn(httpEntity);
        when(httpEntity.getContent()).thenReturn(new ByteArrayInputStream("some output".getBytes("UTF-8")));

        Gson gson = new GsonBuilder().create();
        HttpTemplate httpTemplate = new HttpTemplate(httpClient, gson, null);

        // when
        int statusCode = httpTemplate.get("/some_uri", mock(Consumer.class));

        assertEquals(400, statusCode);
    }

    @Test
    public void testDelete() throws Exception {
        //GIVEN
        String body = "any old body";
        Multimap<String, String> expectedHeaders = LinkedListMultimap.create();
        expectedHeaders.put("foo", "bar");
        expectedHeaders.put("bar", "baz");

        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity entity = mock(HttpEntity.class);

        when(httpClient.execute(isA(HttpDelete.class))).thenReturn(httpResponse);
        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes()));
        when(httpResponse.getEntity()).thenReturn(entity);
        when(httpResponse.getAllHeaders()).thenReturn(new Header[]{new BasicHeader("foo", "bar"), new BasicHeader("bar", "baz")});

        HttpTemplate testClass = new HttpTemplate(httpClient, null, null);

        //WHEN
        Response result = testClass.delete(URI.create("http://lmgtfy.com"));

        //THEN
        assertEquals(new Response(200, body.getBytes(), expectedHeaders), result);
    }

    @Test
    public void testGetWithExtraHeaders() throws Exception {
        //GIVEN
        String body = "result body text";
        Response expected = new Response(200, body.getBytes(), LinkedListMultimap.create());

        HttpResponse response = mock(HttpResponse.class, RETURNS_DEEP_STUBS);
        HttpClient httpClient = mock(HttpClient.class);
        StatusLine statusLine = mock(StatusLine.class);

        AtomicReference<HttpRequest> seenRequest = new AtomicReference<>();
        when(httpClient.execute(isA(HttpGet.class))).thenAnswer(invocation -> {
            HttpRequest httpRequest = (HttpRequest) invocation.getArguments()[0];
            seenRequest.set(httpRequest);
            return response;
        });
        when(response.getAllHeaders()).thenReturn(new Header[0]);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(response.getEntity().getContent()).thenReturn(new ByteArrayInputStream(body.getBytes()));
        when(statusLine.getStatusCode()).thenReturn(200);

        HttpTemplate testClass = new HttpTemplate(httpClient, null, null);

        //WHEN
        URI uri = URI.create("http://service.com/ftw");
        Map<String, String> headers = new HashMap<>();
        headers.put("foo", "bar");
        Response result = testClass.get(uri, x -> {
        }, headers);

        //THEN
        assertEquals(expected, result);
        assertEquals("bar", seenRequest.get().getFirstHeader("foo").getValue());
        assertEquals("application/json", seenRequest.get().getFirstHeader("Accept").getValue());
    }

    @Test
    public void testPostWithExtraHeaders() throws Exception {
        //GIVEN
        Response expected = new Response(200, "body response".getBytes(), ArrayListMultimap.create());
        URI uri = URI.create("http://the-post-target.com");
        Map<String, String> extraHeaders = new HashMap<>();
        extraHeaders.put("SOMETHING", "I'm extra");

        HttpClient client = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class, RETURNS_DEEP_STUBS);

        AtomicReference<HttpPost> seenPost = new AtomicReference<>();
        when(client.execute(any(HttpPost.class))).thenAnswer(invocation -> {
            seenPost.set((HttpPost) invocation.getArguments()[0]);
            return httpResponse;
        });
        when(httpResponse.getStatusLine().getStatusCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(httpResponse.getEntity().getContent()).thenReturn(new ByteArrayInputStream(expected.getBody()));
        when(httpResponse.getAllHeaders()).thenReturn(new Header[0]);

        HttpTemplate testClass = new HttpTemplate(client, null, null);

        //WHEN
        Response result = testClass.post(uri, "body message".getBytes(), "text/plain", extraHeaders);

        //THEN
        assertEquals(expected, result);
        assertEquals("I'm extra", seenPost.get().getFirstHeader("SOMETHING").getValue());
    }

    @Test
    public void testPostNotPassedContentTypeUsesInstanceType() throws Exception {
        //GIVEN
        HttpClient client = mock(HttpClient.class);
        HttpResponse httpResponse = mock(HttpResponse.class);
        StatusLine statusLine = mock(StatusLine.class);
        HttpEntity entity = mock(HttpEntity.class);

        when(httpResponse.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenReturn(entity);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream("here ya go".getBytes()));
        when(httpResponse.getAllHeaders()).thenReturn(new Header[0]);

        AtomicReference<HttpPost> seenPost = new AtomicReference<>();
        when(client.execute(any(HttpPost.class))).thenAnswer(invocation -> {
            seenPost.set((HttpPost) invocation.getArguments()[0]);
            return httpResponse;
        });

        HttpTemplate testClass = new HttpTemplate(client, null, null, "application/awesome", "*/*");

        //WHEN
        testClass.post(URI.create("http://flightstats.com/testtesttest"), "hey its content".getBytes(), EMPTY_MAP);

        //THEN
        assertEquals("application/awesome", seenPost.get().getFirstHeader("content-type").getValue());

    }
}
