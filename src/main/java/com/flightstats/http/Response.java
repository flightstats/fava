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
     *
     * @deprecated use {@link #getBodyString(Charset)} with an explicit Charset value instead.
     */
    @Deprecated
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
        String bodyString = truncate(getBodyString());
        return "Response{" +
                "code=" + code +
                ", body=" + bodyString +
                ", headers=" + headers +
                '}';
    }

    private String truncate(String string) {
        if (string.length() <= MAX_BODY_LENGTH) {
            return string;
        }
        return string.substring(0, MAX_BODY_LENGTH) + "...[snip]...";
    }
}
