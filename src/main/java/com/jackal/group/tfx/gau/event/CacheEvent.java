package com.jackal.group.tfx.gau.event;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CacheEvent {
    private String key;
    private String rawKey;
    private String details;
}