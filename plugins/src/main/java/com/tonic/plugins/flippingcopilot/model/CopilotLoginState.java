package com.tonic.plugins.flippingcopilot.model;

import joptsimple.internal.Strings;
import lombok.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;


@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class CopilotLoginState {

    public LoginResponse loginResponse = null;
    public Map<String,Integer> displayNameToAccountId = new HashMap<>();
    public Map<Integer, String> accountIdToDisplayName = new HashMap<>();

    public int getUserId() {
        return loginResponse != null ? loginResponse.getUserId() : -1;
    }

    public boolean isLoggedIn() {
        // Bypass authentication check - always return true to allow plugin usage without login
        return true;
    }

    // Check if this is a mock/fake login (for offline mode)
    public boolean isMockLogin() {
        return loginResponse == null || "mock-jwt-token-for-offline-mode".equals(loginResponse.getJwt());
    }

    public Set<Integer> accountIds() {
        return accountIdToDisplayName.keySet();
    }

    public String getJwtToken() {
        return !isLoggedIn() ? null : loginResponse.getJwt();
    }

    public Integer getAccountId(String displayName) {
        if(displayName == null) {
            return null;
        }
        return displayNameToAccountId.getOrDefault(displayName, -1);
    }

    public String getDisplayName(Integer accountId) {
        if(accountId == null){
            return null;
        }
        return accountIdToDisplayName.getOrDefault(accountId, "Unknown");
    }

    public CopilotLoginState copy() {
        return new CopilotLoginState(loginResponse, new HashMap<>(displayNameToAccountId), new HashMap<>(accountIdToDisplayName));
    }
}
