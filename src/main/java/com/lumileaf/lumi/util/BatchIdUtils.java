package com.lumileaf.lumi.util;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class BatchIdUtils {

    private BatchIdUtils() {}

    public static List<String> splitSubBatchIds(String joinedId) {
        if (joinedId == null || joinedId.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joinedId.split("\\+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public static String normalizeLotNumber(String raw) {
        if (raw == null) return null;
        return raw.trim().replaceAll("\\s+", " ").toUpperCase();
    }
}