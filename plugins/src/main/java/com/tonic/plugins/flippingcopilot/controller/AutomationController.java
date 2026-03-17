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
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

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
    private int lastInputPromptTick = -1;

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

        if ((hasCollectableOffers() || accountStatus.isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen()))
                && GrandExchangeAPI.canCollect()) {
            GrandExchangeAPI.collectAll();
            suggestionManager.setSuggestionNeeded(true);
            markAction("collect", suggestion);
            return;
        }

        if (handleOfferInputPrompt(suggestion)) {
            return;
        }

        if (!grandExchange.isHomeScreenOpen()) {
            return;
        }

        if (!accountStatus.emptySlotExists()) {
            return;
        }

        if (suggestion.isBuySuggestion() && !accountStatus.isCollectNeeded(suggestion, false)) {
            if (GrandExchangeAPI.startBuyOffer(suggestion.getItemId(), suggestion.getQuantity(), suggestion.getPrice()) != null) {
                markSuggestionActioned(suggestion);
                markAction("start-buy", suggestion);
            }
            return;
        }

        if (suggestion.isSellSuggestion() && !accountStatus.isCollectNeeded(suggestion, false)) {
            if (GrandExchangeAPI.startSellOffer(suggestion.getItemId(), suggestion.getQuantity(), suggestion.getPrice()) != null) {
                markSuggestionActioned(suggestion);
                markAction("start-sell", suggestion);
            }
        }
    }

    private boolean hasCollectableOffers() {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) {
            return false;
        }
        for (GrandExchangeOffer offer : offers) {
            if (offer == null) {
                continue;
            }
            GrandExchangeOfferState state = offer.getState();
            if (state == GrandExchangeOfferState.BOUGHT
                    || state == GrandExchangeOfferState.SOLD
                    || state == GrandExchangeOfferState.CANCELLED_BUY
                    || state == GrandExchangeOfferState.CANCELLED_SELL) {
                return true;
            }
        }
        return false;
    }

    private boolean handleOfferInputPrompt(Suggestion suggestion) {
        if (!offerHandler.isSettingPrice() && !offerHandler.isSettingQuantity()) {
            lastInputPromptTick = -1;
            return false;
        }

        int tick = client.getTickCount();
        if (tick == lastInputPromptTick) {
            return true;
        }

        offerHandler.setSuggestedAction(suggestion);
        client.runScript(138);
        lastInputPromptTick = tick;
        markSuggestionActioned(suggestion);
        suggestionManager.setSuggestionNeeded(true);
        markAction("set-offer-input", suggestion);
        return true;
    }

    private void markSuggestionActioned(Suggestion suggestion) {
        suggestion.actionedTick = client.getTickCount();
        suggestionManager.setSuggestionNeeded(true);
    }

    private void markAction(String action, Suggestion suggestion) {
        lastActionAt = System.currentTimeMillis();
        log.debug("automation action {} for suggestion {} {}", action, suggestion.getType(), suggestion.getItemId());
    }
}
