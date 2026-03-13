package com.flippingcopilot.controller;

import com.flippingcopilot.model.Suggestion;
import com.flippingcopilot.model.SuggestionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AutomationController {

    private static final long ACTION_COOLDOWN_MS = 800L;

    private final SuggestionManager suggestionManager;
    private final HighlightController highlightController;
    private final OfferHandler offerHandler;
    private final GrandExchange grandExchange;
    private final Client client;

    private long lastActionAt = 0L;

    public void onGameTick() {
        if (!grandExchange.isOpen()) {
            return;
        }
        if (System.currentTimeMillis() - lastActionAt < ACTION_COOLDOWN_MS) {
            return;
        }

        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) {
            return;
        }

        if (offerHandler.isSettingPrice() || offerHandler.isSettingQuantity()) {
            offerHandler.setSuggestedAction(suggestion);
            client.runScript(138);
            lastActionAt = System.currentTimeMillis();
            return;
        }

        List<Widget> highlightedWidgets = highlightController.getHighlightedWidgets();
        if (highlightedWidgets.isEmpty()) {
            return;
        }

        Widget widget = highlightedWidgets.get(0);
        if (widget == null || widget.isHidden()) {
            return;
        }

        String option = "Continue";
        if (suggestion.isAbortSuggestion()) {
            option = "Abort offer";
        } else if (suggestion.isModifySuggestion()) {
            option = "Modify offer";
        }

        client.invokeMenuAction(option, "", 1, MenuAction.CC_OP.getId(), -1, widget.getId());
        lastActionAt = System.currentTimeMillis();
        log.debug("automation action {} on widget {}", option, widget.getId());
    }
}
