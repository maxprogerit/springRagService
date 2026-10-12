package com.springrag.common;

import java.util.List;

/** One page of a list plus the total number of items, sent as the body (items) and the X-Total-Count header (total). */
public record PageResult<T>(List<T> items, long total) {

    public static final String TOTAL_HEADER = "X-Total-Count";
    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public static int clampPage(int page) {
        return Math.max(page, 0);
    }
}
