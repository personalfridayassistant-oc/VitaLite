package com.flippingcopilot.model;

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
        return loginResponse != null ? loginResponse.getUserId() : 0;
    }

    public boolean isLoggedIn() {
        return true;
    }

    public Set<Integer> accountIds() {
        return accountIdToDisplayName.keySet();
    }

    public String getJwtToken() {
        return loginResponse == null ? "offline" : loginResponse.getJwt();
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
