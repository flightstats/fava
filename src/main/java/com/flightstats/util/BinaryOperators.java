package com.flightstats.util;

import java.util.function.BinaryOperator;

public class BinaryOperators {

    /**
     * Sometimes you are working with code that just picks the first item out of a stream or
     * just arbitrarily picks one...and sometimes you wish to augment that code with a selection
     * strategy of some kind (reduce()).  This BinaryOperator can help you to do that in a way that
     * doesn't break backwards compatibility.
     *
     * @return A new BinaryOperator<T> that always just picks the first (leftmost) operand.
     */
    public static <T> BinaryOperator<T> firstOne(){
        return (t, t2) -> t;
    }
}
