package com.jackal.group.tfx.gau.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SourceType {
    MISC("misc"),
    UPLOAD("upload"),
    JIRA_ALM("Jira-A"),
    JIRA_IWPB("Jira-W"),
    CONFLUENCE_ALM("Confluence-A"),
    CONFLUENCE_IWPB("Confluence-W"),

    private final String value;

    public static SourceType fromValue(String value) {
        for (SourceType type : SourceType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid source type: " + value);
    }
}