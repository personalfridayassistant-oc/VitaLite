package com.tonic.plugins.flippingcopilot.controller;

import com.tonic.api.widgets.GrandExchangeAPI;
import com.tonic.plugins.flippingcopilot.config.FlippingCopilotConfig;
import com.tonic.plugins.flippingcopilot.model.AccountStatus;
import com.tonic.plugins.flippingcopilot.model.AccountStatusManager;
import com.tonic.plugins.flippingcopilot.model.Suggestion;
import com.tonic.plugins.flippingcopilot.model.SuggestionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AutomationController {

    private static final long ACTION_COOLDOWN_MS = 600L;

    private final FlippingCopilotConfig config;
    private final SuggestionManager suggestionManager;
    private final OfferHandler offerHandler;
    private final GrandExchange grandExchange;
    private final AccountStatusManager accountStatusManager;
    private final Client client;

    private long lastActionAt = 0L;

    public void onGameTick() {
        if (!config.enableAutomation() || !grandExchange.isOpen()) {
            return;
        }
        if (System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) {
            return;
        }

        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null || suggestion.isWaitSuggestion()) {
            return;
        }

        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if (accountStatus == null) {
            return;
        }

        if (accountStatus.isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen()) && GrandExchangeAPI.canCollect()) {
            GrandExchangeAPI.collectAll();
            markAction("collect", suggestion);
            return;
        }

        if (!grandExchange.isHomeScreenOpen()) {
            return;
        }

        if (!accountStatus.emptySlotExists()) {
            return;
        }

        if (offerHandler.isSettingPrice() || offerHandler.isSettingQuantity()) {
            offerHandler.setSuggestedAction(suggestion);
            client.runScript(138);
            markAction("set-offer-input", suggestion);
            return;
        }

        if (suggestion.isBuySuggestion() && !accountStatus.isCollectNeeded(suggestion, false)) {
            if (GrandExchangeAPI.startBuyOffer(suggestion.getItemId(), suggestion.getQuantity(), suggestion.getPrice()) != null) {
                markAction("start-buy", suggestion);
            }
            return;
        }

        if (suggestion.isSellSuggestion() && !accountStatus.isCollectNeeded(suggestion, false)) {
            if (GrandExchangeAPI.startSellOffer(suggestion.getItemId(), suggestion.getQuantity(), suggestion.getPrice()) != null) {
                markAction("start-sell", suggestion);
            }
        }
    }

    private void markAction(String action, Suggestion suggestion) {
        lastActionAt = System.currentTimeMillis();
        log.debug("automation action {} for suggestion {} {}", action, suggestion.getType(), suggestion.getItemId());
    }
}
