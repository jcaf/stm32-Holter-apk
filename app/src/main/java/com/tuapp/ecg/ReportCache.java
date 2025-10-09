package com.tuapp.ecg;

import java.util.HashMap;
import java.util.Map;

public class ReportCache {
    private static final Map<String, Metrics.Report> cache = new HashMap<>();

    public static void put(String key, Metrics.Report rep) {
        cache.put(key, rep);
    }

    public static Metrics.Report get(String key) {
        return cache.remove(key); // lo elimina al leer, para liberar memoria
    }
}
