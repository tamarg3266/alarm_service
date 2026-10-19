package com.septier.alarms.api.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Bounded per-process admission. Client identity is the socket peer; forwarded headers are untrusted. */
@Component
public class RequestAdmission {
    private final Semaphore concurrent;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final int rate, burst, maxClients;
    public RequestAdmission(@Value("${alarm.max-concurrent-requests}") int concurrent,
            @Value("${alarm.requests-per-second}") int rate, @Value("${alarm.request-burst}") int burst,
            @Value("${alarm.max-client-buckets}") int maxClients) {
        if (concurrent < 1 || rate < 1 || burst < 1 || maxClients < 1) throw new IllegalArgumentException("Admission limits must be positive");
        this.concurrent = new Semaphore(concurrent); this.rate = rate; this.burst = burst; this.maxClients = maxClients;
    }
    public boolean enter(String client) { return allow(client) && concurrent.tryAcquire(); }
    public void exit() { concurrent.release(); }
    private synchronized boolean allow(String client) {
        long now = System.nanoTime();
        buckets.values().removeIf(b -> now - b.updated > 120_000_000_000L);
        var bucket = buckets.get(client);
        if (bucket == null) {
            if (buckets.size() >= maxClients) return false;
            bucket = new Bucket(burst, now); buckets.put(client, bucket);
        }
        bucket.tokens = Math.min(burst, bucket.tokens + (now - bucket.updated) / 1_000_000_000.0 * rate);
        bucket.updated = now;
        if (bucket.tokens < 1) return false;
        bucket.tokens--; return true;
    }
    private static class Bucket {
        double tokens; long updated;
        Bucket(double tokens, long updated) { this.tokens = tokens; this.updated = updated; }
    }
}
