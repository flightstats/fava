package com.flightstats.http;

import com.google.common.base.Strings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResponseTest {
    @Test
    public void testToString() {
        Response response = new Response(200, "hello world".getBytes(), null);
        String expected = "Response{code=200, body=hello world, headers=null}";
        assertEquals(expected, response.toString());
    }

    @Test
    public void testToString_EmptyBody() {
        Response response = new Response(200, "".getBytes(), null);
        String expected = "Response{code=200, body=, headers=null}";
        assertEquals(expected, response.toString());
    }

    @Test
    public void testToString_BodyLimit() {
        {
            // at limit
            String body = Strings.repeat("A", Response.MAX_BODY_LENGTH);
            Response response = new Response(200, body.getBytes(), null);
            String expected = "Response{code=200, body=" + body + ", headers=null}";
            assertEquals(expected, response.toString());
        }

        {
            // over limit
            String body = Strings.repeat("A", Response.MAX_BODY_LENGTH + 10);
            Response response = new Response(200, body.getBytes(), null);
            String expected = "Response{code=200, body=" + body.substring(0, Response.MAX_BODY_LENGTH) + ", headers=null}";
            assertEquals(expected, response.toString());
        }
    }
}
