package com.flightstats.util;

import org.junit.Test;

import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

public class BinaryOperatorsTest {

    @Test
    public void testFirstOne() throws Exception {
        assertEquals("foo", BinaryOperators.firstOne().apply("foo", "bar"));
        assertEquals("bip", Stream.of("bip", "bim", "bloop").reduce(BinaryOperators.firstOne()).get());
    }

}