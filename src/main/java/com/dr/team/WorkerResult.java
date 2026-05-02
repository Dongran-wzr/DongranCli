package com.dr.team;

public record WorkerResult(
        boolean success,
        String output,
        String error,
        int iterationsUsed
) {
}
