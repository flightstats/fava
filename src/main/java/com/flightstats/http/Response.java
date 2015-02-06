package com.flightstats.http;


import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import lombok.Value;

import java.nio.charset.Charset;
import java.util.Collection;

@Value
public class Response {
    public static final int MAX_BODY_LENGTH = 250;

    int code;
    byte[] body;
    Multimap<String, String> headers;

    public String getHeader(String location) {
        Collection<String> headers = this.headers.get(location);
        return headers == null ? null : Iterables.getFirst(headers, null);
    }

    public Collection<String> getHeaders(String location) {
        return headers.get(location);
    }

    /**
     * @return The response body as a String by using the platform's default character set.
     */
    public String getBodyString() {
        return getBodyString(Charset.defaultCharset());
    }

    /**
     * @return The response body as a String by using the specified character encoding.
     */
    public String getBodyString(Charset charset) {
        return new String(body, charset);
    }

    @Override
    public String toString() {
        String bodyString = truncate(getBodyString(), MAX_BODY_LENGTH);
        return "Response{" +
                "code=" + code +
                ", body=" + bodyString +
                ", headers=" + headers +
                '}';
    }

    private String truncate(String str, final int length) {
        int endIndex = Math.min(str.length(), length);
        return str.substring(0, endIndex);
    }
}