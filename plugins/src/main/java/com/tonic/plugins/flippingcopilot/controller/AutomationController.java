package com.tonic.plugins.flippingcopilot.controller;

import com.tonic.api.TClient;
import com.tonic.plugins.flippingcopilot.config.FlippingCopilotConfig;
import com.tonic.plugins.flippingcopilot.model.AccountStatus;
<<<<<<< codex/implement-automatic-purchasing-and-selling-in-vitalite-api-wgjj02
import com.tonic.plugins.flippingcopilot.model.AccountStatusManager;
=======
>>>>>>> main
import com.tonic.plugins.flippingcopilot.model.GEOfferScreenSetupOfferState;
import com.tonic.plugins.flippingcopilot.model.Suggestion;
import com.tonic.plugins.flippingcopilot.model.SuggestionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.widgets.Widget;

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

        if (offerHandler.isSettingPrice() || offerHandler.isSettingQuantity()) {
            offerHandler.setSuggestedAction(suggestion);
            client.runScript(138);
            markAction("set-offer-input", suggestion);
            return;
        }

        if (grandExchange.isHomeScreenOpen()) {
            actionHomeScreen(suggestion);
            return;
        }

        if (grandExchange.isSetupOfferOpen()) {
            GEOfferScreenSetupOfferState setupState = grandExchange.getOfferScreenSetupOfferState();
            if (setupState != null && setupState.offerDetailsCorrect(suggestion)) {
                clickWidget(grandExchange.getConfirmButton(), "Confirm");
                markAction("confirm-offer", suggestion);
            }
        }
    }

    private void actionHomeScreen(Suggestion suggestion) {
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if (accountStatus == null) {
            return;
        }

        if (accountStatus.isCollectNeeded(suggestion, false) && grandExchange.isCollectButtonVisible()) {
            clickWidget(grandExchange.getCollectButton(), "Collect");
            markAction("collect", suggestion);
            return;
        }

        if (suggestion.isBuySuggestion() && accountStatus.emptySlotExists()) {
            clickWidget(grandExchange.getBuyButton(accountStatus.findEmptySlot()), "Buy");
            markAction("start-buy", suggestion);
            return;
        }

        if (suggestion.isSellSuggestion()) {
            Widget itemWidget = getInventoryItemWidget(suggestion.getItemId());
            if (itemWidget != null) {
                clickWidget(itemWidget, "Offer");
                markAction("start-sell", suggestion);
            }
        }
    }

    private void clickWidget(Widget widget, String preferredOption) {
        if (!(client instanceof TClient) || widget == null || widget.isHidden()) {
            return;
        }
        int identifier = resolveActionIdentifier(widget, preferredOption);
        ((TClient) client).invokeMenuAction(
                preferredOption,
                "",
                identifier,
                MenuAction.CC_OP.getId(),
                -1,
                widget.getId(),
                -1,
                -1,
                -1
        );
    }

    private int resolveActionIdentifier(Widget widget, String preferredOption) {
        String[] actions = widget.getActions();
        if (actions == null) {
            return 1;
        }
        for (int i = 0; i < actions.length; i++) {
            if (actions[i] != null && actions[i].equalsIgnoreCase(preferredOption)) {
                return i + 1;
            }
        }
        return 1;
    }

    private Widget getInventoryItemWidget(int unnotedItemId) {
        Widget inventory = client.getWidget(467, 0);
        if (inventory == null) {
            inventory = client.getWidget(149, 0);
            if (inventory == null) {
                return null;
            }
        }

        for (Widget widget : inventory.getDynamicChildren()) {
            int itemId = widget.getItemId();
            ItemComposition itemComposition = client.getItemDefinition(itemId);
            if (itemComposition.getNote() != -1 && itemComposition.getLinkedNoteId() == unnotedItemId) {
                return widget;
            }
            if (itemId == unnotedItemId) {
                return widget;
            }
        }
        return null;
    }

    private void markAction(String action, Suggestion suggestion) {
        lastActionAt = System.currentTimeMillis();
        log.debug("automation action {} for suggestion {} {}", action, suggestion.getType(), suggestion.getItemId());
    }
}
