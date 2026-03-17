package com.tonic.plugins.flippingcopilot.controller;

import com.tonic.api.widgets.GrandExchangeAPI;
import com.tonic.data.GrandExchangeSlot;
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
    private PendingOffer pendingOffer;

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

        if (suggestion.isAbortSuggestion()) {
            handleAbortSuggestion(suggestion);
            return;
        }

        if (suggestion.isModifySuggestion()) {
            handleModifySuggestion(suggestion);
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

    private void handleAbortSuggestion(Suggestion suggestion) {
        int slotNumber = suggestion.getBoxId() + 1;
        GrandExchangeSlot slot = GrandExchangeSlot.getBySlot(slotNumber);
        if (slot == null) {
            return;
        }
        GrandExchangeAPI.cancel(slot);
        if (GrandExchangeAPI.canCollect()) {
            GrandExchangeAPI.collectAll();
        }
        markSuggestionActioned(suggestion);
        markAction("abort-offer", suggestion);
    }

    private void handleModifySuggestion(Suggestion suggestion) {
        if (pendingOffer != null && pendingOffer.matches(suggestion)) {
            if (tryPlacePendingOffer(suggestion)) {
                pendingOffer = null;
            }
            return;
        }

        int slotNumber = suggestion.getBoxId() + 1;
        GrandExchangeSlot slot = GrandExchangeSlot.getBySlot(slotNumber);
        if (slot == null) {
            return;
        }

        GrandExchangeAPI.cancel(slot);
        if (GrandExchangeAPI.canCollect()) {
            GrandExchangeAPI.collectAll();
        }
        pendingOffer = PendingOffer.fromSuggestion(suggestion);
        markSuggestionActioned(suggestion);
        markAction("modify-cancel", suggestion);
    }

    private boolean tryPlacePendingOffer(Suggestion suggestion) {
        if (!grandExchange.isHomeScreenOpen()) {
            return false;
        }
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if (accountStatus == null || !accountStatus.emptySlotExists()) {
            return false;
        }

        if (pendingOffer.sell) {
            if (GrandExchangeAPI.startSellOffer(pendingOffer.itemId, pendingOffer.quantity, pendingOffer.price) != null) {
                markSuggestionActioned(suggestion);
                markAction("modify-resubmit-sell", suggestion);
                return true;
            }
        } else {
            if (GrandExchangeAPI.startBuyOffer(pendingOffer.itemId, pendingOffer.quantity, pendingOffer.price) != null) {
                markSuggestionActioned(suggestion);
                markAction("modify-resubmit-buy", suggestion);
                return true;
            }
        }
        return false;
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

    private static class PendingOffer {
        private final boolean sell;
        private final int itemId;
        private final int quantity;
        private final int price;
        private final int suggestionId;

        private PendingOffer(boolean sell, int itemId, int quantity, int price, int suggestionId) {
            this.sell = sell;
            this.itemId = itemId;
            this.quantity = quantity;
            this.price = price;
            this.suggestionId = suggestionId;
        }

        private static PendingOffer fromSuggestion(Suggestion suggestion) {
            return new PendingOffer(
                    suggestion.isSellSuggestion(),
                    suggestion.getItemId(),
                    suggestion.getQuantity(),
                    suggestion.getPrice(),
                    suggestion.getId());
        }

        private boolean matches(Suggestion suggestion) {
            return suggestionId == suggestion.getId();
        }
    }
}
