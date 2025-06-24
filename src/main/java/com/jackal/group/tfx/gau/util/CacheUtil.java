package com.jackal.group.tfx.gau.util;

import com.jackal.group.tfx.gau.exception.CustomBaseException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class CacheUtil {
    private CacheUtil() {}
    public static String checksum(String data) {
        try {
            var bytes = MessageDigest.getInstance("MD5").digest(data.getBytes(StandardCharsets.UTF_8));
            // return hex representation of the bytes
            var sb = new StringBuilder();
            for (var b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new CustomBaseException(500, "Internal server error: No MD5 algorithm found");
        }
    }

    public static <T> T monoBlock(Mono<T> mono) {
        var ref = new AtomicReference<T>();
        try {
            CompletableFuture.runAsync(() -> ref.set(mono.block())).join();
            return ref.get();
        } catch (Exception e) {
            throw new CustomBaseException(500, "monoBlock error: " + e.getMessage());
        }
    }
}
