package com.example.auth.clientadmin;

import java.time.Instant;
import java.util.List;

/**
 * 列表读模型；不含任何 secret。
 */
public record ClientSummaryView(String clientId, String clientName, String type, List<String> grantTypes,
                                List<String> scopes, boolean enabled, Instant updatedAt, String updatedBy) {
}
