package com.dr.web;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class NetworkPolicy {
    private static final Set<String> SCHEME_ALLOWLIST = Set.of("http", "https");
    private static final Set<String> HOST_BLOCKLIST = Set.of(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "metadata.google.internal",
            "169.254.169.254"
    );
    private final TokenBucket tokenBucket;

    public NetworkPolicy(int capacity, double refillPerSecond) {
        this.tokenBucket = new TokenBucket(capacity, refillPerSecond);
    }

    public void assertAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 非法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEME_ALLOWLIST.contains(scheme)) {
            throw new SecurityException("scheme 不在白名单中");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("host 为空");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (HOST_BLOCKLIST.contains(normalizedHost)) {
            throw new SecurityException("host 命中黑名单");
        }
        checkDns(normalizedHost);
    }

    public void acquireToken() {
        if (!tokenBucket.tryAcquire(1)) {
            throw new SecurityException("网络请求限流，请稍后再试");
        }
    }

    private void checkDns(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isAnyLocalAddress()
                        || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new SecurityException("DNS 解析到内网/本地地址，疑似 SSRF");
                }
                byte[] bytes = addr.getAddress();
                if (bytes.length == 16) {
                    String txt = addr.getHostAddress().toLowerCase(Locale.ROOT);
                    if (txt.startsWith("fc") || txt.startsWith("fd") || txt.startsWith("fe80")) {
                        throw new SecurityException("DNS 解析到 IPv6 私网地址，疑似 SSRF");
                    }
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("DNS 校验失败");
        }
    }

    private static class TokenBucket {
        private final int capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastNanos;

        TokenBucket(int capacity, double refillPerSecond) {
            this.capacity = Math.max(1, capacity);
            this.refillPerSecond = Math.max(0.1, refillPerSecond);
            this.tokens = this.capacity;
            this.lastNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire(int n) {
            refill();
            if (tokens >= n) {
                tokens -= n;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            double sec = (now - lastNanos) / 1_000_000_000.0;
            if (sec <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + sec * refillPerSecond);
            lastNanos = now;
        }
    }
}
