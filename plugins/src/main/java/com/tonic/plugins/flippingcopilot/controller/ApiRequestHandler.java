package com.tonic.plugins.flippingcopilot.controller;

import com.tonic.plugins.flippingcopilot.model.*;
import com.tonic.plugins.flippingcopilot.rs.CopilotLoginRS;
import com.tonic.plugins.flippingcopilot.ui.graph.model.Data;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import okhttp3.*;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;


@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ApiRequestHandler {

    private static final String serverUrl = System.getenv("FLIPPING_COPILOT_HOST") != null ? System.getenv("FLIPPING_COPILOT_HOST")  : "https://api.flippingcopilot.com";
    private static final String serverFeUrl = serverUrl.replace("api.", "");
    private static final String OSRS_WIKI_PRICES_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String OSRS_ITEM_DETAIL_URL = "https://secure.runescape.com/m=itemdb_oldschool/api/catalogue/detail.json?item=";
    private static final String OSRS_WIKI_USER_AGENT = "flipping-copilot";
    private static final long ITEM_DETAIL_CACHE_MS = TimeUnit.HOURS.toMillis(2);
    private static final int DETAIL_SCAN_LIMIT = 40;
    public static final String DEFAULT_COPILOT_PRICE_ERROR_MESSAGE = "Unable to fetch price copilot price (possible server update)";
    public static final String DEFAULT_PREMIUM_INSTANCE_ERROR_MESSAGE = "Error loading premium instance data (possible server update)";
    public static final String UNKNOWN_ERROR = "Unknown error";
    public static final int UNAUTHORIZED_CODE = 401;
    // dependencies
    private final OkHttpClient client;
    private final Gson gson;
    private final CopilotLoginRS copilotLoginRS;
    private final SuggestionPreferencesManager preferencesManager;
    private final ClientThread clientThread;
    private final Map<Integer, CachedItemDetail> itemDetailCache = new HashMap<>();


    public void authenticate(String username, String password, Consumer<LoginResponse> successCallback, Consumer<String> failureCallback) {
        Request request = new Request.Builder()
                .url(serverUrl + "/login")
                .addHeader("Authorization", Credentials.basic(username, password))
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), ""))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                failureCallback.accept(UNKNOWN_ERROR);
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE) {
                            copilotLoginRS.clear();
                        }
                        log.warn("login failed with http status code {}", response.code());
                        String errorMessage = extractErrorMessage(response);
                        failureCallback.accept(errorMessage);
                        return;
                    }
                    String body = response.body() == null ? "" : response.body().string();
                    LoginResponse loginResponse = gson.fromJson(body, LoginResponse.class);
                    successCallback.accept(loginResponse);
                } catch (IOException | JsonParseException e) {
                    log.warn("error reading/decoding login response body", e);
                    failureCallback.accept(UNKNOWN_ERROR);
                }
            }
        });
    }

    public Call discordLoginAsync(Consumer<String> oathUrlConsumer,
                                  Consumer<LoginResponse> loginResponseConsumer,
                                  Consumer<HttpResponseException>  onFailure) {
        log.debug("sending request to login via discord");
        Request r = new Request.Builder()
                .url(serverFeUrl + "/v1/plugin-discord-login")
                .get().build();

        Call call = client.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                .newCall(r);

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("login via discord call failed", e);
                clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR)));
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE) {
                            copilotLoginRS.clear();
                        }
                        log.warn("login via discord call failed with http status code {}", response.code());
                        clientThread.invoke(() -> onFailure.accept(new HttpResponseException(response.code(), extractErrorMessage(response))));
                        return;
                    }
                    if (response.body() == null) {
                        throw new IOException("empty discord login response");
                    }
                    try(DataInputStream is = new DataInputStream(new BufferedInputStream(response.body().byteStream()))) {
                        PluginDiscordLoginInitResponse initResponse = PluginDiscordLoginInitResponse.fromRaw(is);
                        clientThread.invoke(() -> oathUrlConsumer.accept(initResponse.getUrl()));
                        LoginResponse loginResponse = LoginResponse.fromRaw(is);
                        if (loginResponse.getError() != null && !loginResponse.getError().isEmpty()) {
                            clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, loginResponse.getError())));
                        } else {
                            clientThread.invoke(() -> loginResponseConsumer.accept(loginResponse));
                        }
                    }
                } catch (Exception e) {
                    log.warn("error reading/parsing discord login response body", e);
                    clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR)));
                }
            }
        });

        return call;
    }

    public void getSuggestionAsync(JsonObject status,
                                   Consumer<Suggestion> suggestionConsumer,
                                   Consumer<Data> graphDataConsumer,
                                   Consumer<HttpResponseException>  onFailure,
                                   boolean skipGraphData) {
        log.debug("sending status {}", status.toString());
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request.Builder rb = new Request.Builder()
                .url(serverUrl + "/suggestion")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .addHeader("Accept", "application/x-msgpack")
                .addHeader("X-VERSION", "1")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), status.toString()));

        if(skipGraphData){
            rb.addHeader("X-SKIP-GD", "true");
        }

        Request request = rb.build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("call to get suggestion failed", e);
                clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR)));
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.warn("get suggestion failed with http status code {}", response.code());
                        clientThread.invoke(() -> onFailure.accept(new HttpResponseException(response.code(), extractErrorMessage(response))));
                        return;
                    }
                    handleSuggestionResponse(response, suggestionConsumer, graphDataConsumer);
                } catch (Exception e) {
                    log.warn("error reading/parsing suggestion response body", e);
                    clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR)));
                }
            }
        });
    }

    private void handleSuggestionResponse(Response response, Consumer<Suggestion> suggestionConsumer, Consumer<Data> graphDataConsumer) throws IOException {
        if (response.body() == null) {
            throw new IOException("empty suggestion request response");
        }
        String contentType = response.header("Content-Type");
        Suggestion s;
        if (contentType != null && contentType.contains("application/x-msgpack")) {
            int contentLength = resolveContentLength(response);
            int suggestionContentLength = resolveSuggestionContentLength(response);
            int graphDataContentLength = contentLength - suggestionContentLength;
            log.debug("msgpack suggestion response size is: {}, suggestion size is {}", contentLength, suggestionContentLength);

            Data d = new Data();
            try(InputStream is = response.body().byteStream()) {
                // This is some bespoke handling to make the user experience better. We basically pack two different
                // objects in the response body. The suggestion (first object) and the graph data (second
                // object). The graph data can be a few kb, and we want the suggestion to be displayed
                // immediately, without having to wait for the graph data to be loaded.

                byte[] suggestionBytes = new byte[suggestionContentLength];
                int bytesRead = is.readNBytes(suggestionBytes, 0, suggestionContentLength);
                if (bytesRead != suggestionContentLength) {
                    throw new IOException("failed to read complete suggestion content: " + bytesRead + " of " + suggestionContentLength + " bytes");
                }
                s = Suggestion.fromMsgPack(ByteBuffer.wrap(suggestionBytes));
                log.debug("suggestion received");
                clientThread.invoke(() -> suggestionConsumer.accept(s));

                if (graphDataContentLength == 0) {
                    d.loadingErrorMessage = "No graph data loaded for this item.";
                } else {
                    try {
                        byte[] remainingBytes = is.readAllBytes();
                        if (graphDataContentLength != remainingBytes.length) {
                            log.error("the graph data bytes read {} doesn't match the expected bytes {}", bytesRead, graphDataContentLength);
                            d.loadingErrorMessage = "There was an issue loading the graph data for this item.";
                        } else {
                            try {
                                d = Data.fromMsgPack(ByteBuffer.wrap(remainingBytes));
                                log.debug("graph data received");
                            } catch (Exception e) {
                                log.error("error deserializing graph data", e);
                                d.loadingErrorMessage = "There was an issue loading the graph data for this item.";
                            }
                        }
                    } catch (IOException e) {
                        log.error("error on reading graph data bytes from the suggestion response", e);
                        d.loadingErrorMessage = "There was an issue loading the graph data for this item.";
                    }
                }
            }
            if (s != null && "wait".equals(s.getType())){
                d.fromWaitSuggestion = true;
            }
            Data finalD = d;
            clientThread.invoke(() -> graphDataConsumer.accept(finalD));
        } else {
            String body = response.body().string();
            log.debug("json suggestion response size is: {}", body.getBytes().length);
            s = gson.fromJson(body, Suggestion.class);
            clientThread.invoke(() -> suggestionConsumer.accept(s));
            Data d = new Data();
            d.loadingErrorMessage = "No graph data loaded for this item.";
            clientThread.invoke(() -> graphDataConsumer.accept(d));
        }
    }

    private int resolveContentLength(Response resp) throws IOException {
        try {
            String cl = resp.header("Content-Length");
            return Integer.parseInt(cl != null ? cl : "missing Content-Length header");
        } catch (NumberFormatException  e) {
            throw new IOException("Failed to parse response Content-Length", e);
        }
    }

    private int resolveSuggestionContentLength(Response resp) throws IOException {
        try {
            String cl = resp.header("X-Suggestion-Content-Length");
            return Integer.parseInt(cl != null ? cl : "missing Content-Length header");
        } catch (NumberFormatException  e) {
            throw new IOException("Failed to parse response Content-Length", e);
        }
    }

    public void sendTransactionsAsync(List<Transaction> transactions, String displayName, BiConsumer<Integer, List<FlipV2>> onSuccess, Consumer<HttpResponseException> onFailure) {
        log.debug("sending {} transactions for display name {}", transactions.size(), displayName);
        JsonArray body = new JsonArray();
        for (Transaction transaction : transactions) {
            body.add(transaction.toJsonObject());
        }
        Integer userId = copilotLoginRS.get().getUserId();
        String jwtToken = copilotLoginRS.get().getJwtToken();
        String encodedDisplayName = URLEncoder.encode(displayName, StandardCharsets.UTF_8);
        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/client-transactions?display_name=" + encodedDisplayName)
                .addHeader("Authorization", "Bearer " + jwtToken)
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .header("Accept", "application/x-bytes")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("call to sync transactions failed", e);
                onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR));
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        String errorMessage = extractErrorMessage(response);
                        log.warn("call to sync transactions failed status code {}, error message {}", response.code(), errorMessage);
                        onFailure.accept(new HttpResponseException(response.code(), errorMessage));
                        return;
                    }
                    List<FlipV2> changedFlips = FlipV2.listFromRaw(response.body().bytes());
                    onSuccess.accept(userId, changedFlips);
                } catch (Exception e) {
                    log.warn("error reading/parsing sync transactions response body", e);
                    onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR));
                }
            }
        });
    }

    private String extractErrorMessage(Response response) {
        if (response.body() != null) {
            try {
                String bodyStr = response.body().string();
                JsonObject errorJson = gson.fromJson(bodyStr, JsonObject.class);
                if (errorJson.has("message")) {
                    return errorJson.get("message").getAsString();
                }
            } catch (Exception e) {
                log.warn("failed reading/parsing error message from http {} response body", response.code(), e);
            }
        }
        return UNKNOWN_ERROR;
    }


    public void asyncGetVisualizeFlipData(UUID flipID, String displayName, Consumer<VisualizeFlipResponse> onSuccess, Consumer<String> onFailure) {
        JsonObject body = new JsonObject();
        body.add("flip_id", new JsonPrimitive(flipID.toString()));
        body.add("display_name", new JsonPrimitive(displayName));
        log.debug("requesting visualize data for flip {}", flipID);
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl +"/profit-tracking/visualize-flip")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .addHeader("Accept", "application/x-msgpack")
                .addHeader("X-VERSION", "1")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .build();

        client.newBuilder()
                .callTimeout(30, TimeUnit.SECONDS) // Overall timeout
                .build()
                .newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        onFailure.accept(e.toString());
                    }
                    @Override
                    public void onResponse(Call call, Response response) {
                        try {
                            if (!response.isSuccessful()) {
                                if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                                    copilotLoginRS.clear();
                                }
                                log.error("get visualize data for flip {} failed with http status code {}", flipID, response.code());
                                onFailure.accept(UNKNOWN_ERROR);
                            } else {
                                byte[] d = response.body().bytes();
                                VisualizeFlipResponse rsp = VisualizeFlipResponse.fromMsgPack(ByteBuffer.wrap(d));
                                log.debug("visualize data received for flip {}", flipID);
                                onSuccess.accept(rsp);
                            }
                        } catch (Exception e) {
                            log.error("error visualize data received for flip {}", flipID, e);
                            onFailure.accept(UNKNOWN_ERROR);
                        }
                    }
                });
    }

    public void asyncGetItemPriceWithGraphData(int itemId, String displayName, Consumer<ItemPrice> consumer, boolean includeGraphData) {
        JsonObject body = new JsonObject();
        body.add("item_id", new JsonPrimitive(itemId));
        body.add("display_name", new JsonPrimitive(displayName));
        body.addProperty("f2p_only", preferencesManager.isF2pOnlyMode());
        body.addProperty("timeframe_minutes", preferencesManager.getTimeframe());
        body.addProperty("risk_level", preferencesManager.getRiskLevel().toApiValue());
        body.addProperty("include_graph_data", includeGraphData);
        log.debug("requesting price graph data for item {}", itemId);
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl +"/prices")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .addHeader("Accept", "application/x-msgpack")
                .addHeader("X-VERSION", "1")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .build();

        client.newBuilder()
                .callTimeout(30, TimeUnit.SECONDS) // Overall timeout
                .build()
                .newCall(request)
                .enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("error fetching copilot price for item {}", itemId, e);
                ItemPrice ip = new ItemPrice(0, 0, DEFAULT_COPILOT_PRICE_ERROR_MESSAGE, null);
                clientThread.invoke(() -> consumer.accept(ip));
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.error("get copilot price for item {} failed with http status code {}", itemId, response.code());
                        ItemPrice ip = new ItemPrice(0, 0, DEFAULT_COPILOT_PRICE_ERROR_MESSAGE, null);
                        clientThread.invoke(() -> consumer.accept(ip));
                    } else {
                        byte[] d = response.body().bytes();
                        ItemPrice ip = ItemPrice.fromMsgPack(ByteBuffer.wrap(d));
                        log.debug("price graph data received for item {}", itemId);
                        clientThread.invoke(() -> consumer.accept(ip));
                    }
                } catch (Exception e) {
                    log.error("error fetching copilot price for item {}", itemId, e);
                    ItemPrice ip = new ItemPrice(0, 0, DEFAULT_COPILOT_PRICE_ERROR_MESSAGE, null);
                    clientThread.invoke(() -> consumer.accept(ip));
                }
            }
        });
    }


    public void asyncUpdatePremiumInstances(Consumer<PremiumInstanceStatus> consumer, List<String> displayNames) {
        JsonObject payload = new JsonObject();
        JsonArray arr = new JsonArray();
        displayNames.forEach(arr::add);
        payload.add("premium_display_names", arr);
        String jwtToken = copilotLoginRS.get().getJwtToken();

        Request request = new Request.Builder()
                .url(serverUrl +"/premium-instances/update-assignments")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), payload.toString()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("error updating premium instance assignments", e);
                clientThread.invoke(() -> consumer.accept(PremiumInstanceStatus.ErrorInstance(DEFAULT_PREMIUM_INSTANCE_ERROR_MESSAGE)));
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.error("update premium instances failed with http status code {}", response.code());
                        clientThread.invoke(() -> consumer.accept(PremiumInstanceStatus.ErrorInstance(DEFAULT_PREMIUM_INSTANCE_ERROR_MESSAGE)));
                    } else {
                        PremiumInstanceStatus ip = gson.fromJson(response.body().string(), PremiumInstanceStatus.class);
                        clientThread.invoke(() -> consumer.accept(ip));
                    }
                } catch (Exception e) {
                    log.error("error updating premium instance assignments", e);
                    clientThread.invoke(() -> consumer.accept(PremiumInstanceStatus.ErrorInstance(DEFAULT_PREMIUM_INSTANCE_ERROR_MESSAGE)));
                }
            }
        });
    }

    public void asyncGetPremiumInstanceStatus(Consumer<PremiumInstanceStatus> consumer) {
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl +"/premium-instances/status")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("error fetching premium instance status", e);
                clientThread.invoke(() -> consumer.accept(PremiumInstanceStatus.ErrorInstance(DEFAULT_PREMIUM_INSTANCE_ERROR_MESSAGE)));
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.error("get premium instance status failed with http status code {}", response.code());
                        clientThread.invoke(() -> consumer.accept(PremiumInstanceStatus.ErrorInstance(DEFAULT_PREMIUM_INSTANCE_ERROR_MESSAGE)));
                    } else {
                        PremiumInstanceStatus ip = gson.fromJson(response.body().string(), PremiumInstanceStatus.class);
                        clientThread.invoke(() -> consumer.accept(ip));
                    }
                } catch (Exception e) {
                    log.error("error fetching premium instance status", e);
                    clientThread.invoke(() -> consumer.accept(PremiumInstanceStatus.ErrorInstance(DEFAULT_PREMIUM_INSTANCE_ERROR_MESSAGE)));
                }
            }
        });

    }

    public void asyncDeleteFlip(FlipV2 flip, Consumer<FlipV2> onSuccess, Runnable onFailure) {
        JsonObject body = new JsonObject();
        body.addProperty("flip_id", flip.getId().toString());
        String jwtToken = copilotLoginRS.get().getJwtToken();

        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/delete-flip")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/x-bytes")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("deleting flip {}", flip.getId(), e);
                onFailure.run();
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.error("deleting flip {}, bad response code {}", flip.getId(), response.code());
                        onFailure.run();
                    } else {
                        FlipV2 flip = FlipV2.fromRaw(response.body().bytes());
                        onSuccess.accept(flip);
                    }
                } catch (Exception e) {
                    log.error("deleting flip {}", flip.getId(), e);
                    onFailure.run();
               }
            }
        });
    }

    public void asyncDeleteAccount(int accountId, Runnable onSuccess, Runnable onFailure) {
        JsonObject body = new JsonObject();
        body.addProperty("account_id", accountId);
        String jwtToken = copilotLoginRS.get().getJwtToken();

        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/delete-account")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/x-bytes")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("deleting account {}", accountId, e);
                onFailure.run();
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.error("deleting account {}, bad response code {}", accountId, response.code());
                        onFailure.run();
                    }
                    onSuccess.run();
                } catch (Exception e) {
                    log.error("deleting account {}", accountId, e);
                    onFailure.run();
                }
            }
        });
    }

    public void asyncLoadAccounts(Consumer<Map<String, Integer>> onSuccess, Consumer<String> onFailure) {
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/rs-account-names")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .method("GET", null)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("error loading user display names", e);
                onFailure.accept(UNKNOWN_ERROR);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        String errorMessage = extractErrorMessage(response);
                        log.error("load user display names failed with http status code {}, error message {}", response.code(), errorMessage);
                        onFailure.accept(errorMessage);
                        return;
                    }
                    String responseBody = response.body() != null ? response.body().string() : "{}";
                    Type respType = new TypeToken<Map<String, Integer>>(){}.getType();
                    Map<String, Integer> names = gson.fromJson(responseBody, respType);
                    Map<String, Integer> result = names != null ? names : new HashMap<>();
                    onSuccess.accept(result);
                } catch (Exception e) {
                    log.error("error reading/parsing user display names response body", e);
                    onFailure.accept(UNKNOWN_ERROR);
                }
            }
        });
    }

    public void asyncLoadFlips(Map<Integer, Integer> accountIdTime, BiConsumer<Integer, FlipsDeltaResult> onSuccess, Consumer<String> onFailure) {
        Integer userId = copilotLoginRS.get().getUserId();
        String jwtToken = copilotLoginRS.get().getJwtToken();
        DataDeltaRequest body = new DataDeltaRequest(accountIdTime);
        String bodyStr = gson.toJson(body);

        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/client-flips-delta")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/x-bytes")
                .method("POST", RequestBody.create(MediaType.get("application/json; charset=utf-8"), bodyStr))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("error loading flips", e);
                onFailure.accept(UNKNOWN_ERROR);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        String errorMessage = extractErrorMessage(response);
                        log.error("load flips failed with http status code {}, error message {}", response.code(), errorMessage);
                        onFailure.accept(errorMessage);
                        return;
                    }
                    FlipsDeltaResult res = FlipsDeltaResult.fromRaw(response.body().bytes());
                    onSuccess.accept(userId, res);
                } catch (Exception e) {
                    log.error("error reading/parsing flips response body", e);
                    onFailure.accept(UNKNOWN_ERROR);
                }
            }
        });
    }

    public void asyncLoadTransactionsData(Consumer<byte[]> onSuccess, Consumer<String> onFailure) {
        String jwtToken = copilotLoginRS.get().getJwtToken();

        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/client-transactions")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/x-bytes")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("error loading transactions", e);
                onFailure.accept(UNKNOWN_ERROR);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        String errorMessage = extractErrorMessage(response);
                        log.error("load transactions failed with http status code {}, error message {}", response.code(), errorMessage);
                        onFailure.accept(errorMessage);
                        return;
                    }
                    byte[] data = response.body().bytes();
                    onSuccess.accept(Arrays.copyOfRange(data, 4, data.length-4));
                } catch (Exception e) {
                    log.error("error reading/parsing transactions response body", e);
                    onFailure.accept(UNKNOWN_ERROR);
                }
            }
        });
    }

    public Call asyncConsumeDumpAlerts(String displayName, Consumer<Response> onSuccess, Consumer<HttpResponseException> onFailure) {
        String encodedDisplayName = URLEncoder.encode(displayName, StandardCharsets.UTF_8);
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl + "/dump-alerts?display_name=" + encodedDisplayName)
                .addHeader("Authorization", "Bearer " + jwtToken)
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), ""))
                .build();

        Call call = client.newBuilder()
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request);

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("error consuming dump alerts", e);
                onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR));
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                        copilotLoginRS.clear();
                    }
                    String errorMessage = extractErrorMessage(response);
                    response.close();
                    onFailure.accept(new HttpResponseException(response.code(), errorMessage));
                    return;
                }
                if (response.body() == null) {
                    response.close();
                    onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR));
                    return;
                }
                onSuccess.accept(response);
            }
        });

        return call;
    }


    public void asyncOrphanTransaction(AckedTransaction transaction, BiConsumer<Integer, List<FlipV2>> onSuccess, Runnable onFailure) {
        JsonObject body = new JsonObject();
        body.addProperty("transaction_id", transaction.getId().toString());
        body.addProperty("account_id", transaction.getAccountId());
        Integer userId = copilotLoginRS.get().getUserId();
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/orphan-transaction")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/x-bytes")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("orphaning transaction {}", transaction.getId(), e);
                onFailure.run();
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.error("orphaning transaction {}, bad response code {}", transaction.getId(), response.code());
                        onFailure.run();
                    } else {
                        List<FlipV2> flips = FlipV2.listFromRaw(response.body().bytes());
                        onSuccess.accept(userId, flips);
                    }
                } catch (Exception e) {
                    log.error("orphaning transaction {}", transaction.getId(), e);
                    onFailure.run();
                }
            }
        });
    }

    public void asyncDeleteTransaction(AckedTransaction transaction, BiConsumer<Integer, List<FlipV2>> onSuccess, Runnable onFailure) {
        JsonObject body = new JsonObject();
        body.addProperty("transaction_id", transaction.getId().toString());
        body.addProperty("account_id", transaction.getAccountId());
        Integer userId = copilotLoginRS.get().getUserId();
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/delete-transaction")
                .addHeader("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/x-bytes")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("delete transaction {}", transaction.getId(), e);
                onFailure.run();
            }
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        log.error("delete transaction {}, bad response code {}", transaction.getId(), response.code());
                        onFailure.run();
                    } else {
                        List<FlipV2> flips = FlipV2.listFromRaw(response.body().bytes());
                        onSuccess.accept(userId, flips);
                    }
                } catch (Exception e) {
                    log.error("delete transaction {}", transaction.getId(), e);
                    onFailure.run();
                }
            }
        });
    }

    public void asyncLoadRecentAccountTransactions(String displayName, int endTime, Consumer<List<AckedTransaction>> onSuccess, Consumer<String> onFailure) {
        JsonObject body = new JsonObject();
        body.addProperty("limit", 30);
        body.addProperty("end", endTime);
        String jwtToken = copilotLoginRS.get().getJwtToken();
        Request request = new Request.Builder()
                .url(serverUrl + "/profit-tracking/account-client-transactions?display_name=" + displayName)
                .addHeader("Authorization", "Bearer " + jwtToken)
                .header("Accept", "application/x-bytes")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), body.toString()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("error loading transactions", e);
                onFailure.accept(UNKNOWN_ERROR);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        if(response.code() == UNAUTHORIZED_CODE && Objects.equals(jwtToken, copilotLoginRS.get().getJwtToken())) {
                            copilotLoginRS.clear();
                        }
                        String errorMessage = extractErrorMessage(response);
                        log.error("load transactions failed with http status code {}, error message {}", response.code(), errorMessage);
                        onFailure.accept(errorMessage);
                        return;
                    }
                    onSuccess.accept(AckedTransaction.listFromRaw(response.body().bytes()));
                } catch (Exception e) {
                    log.error("error reading/parsing transactions response body", e);
                    onFailure.accept(UNKNOWN_ERROR);
                }
            }
        });
    }

    /**
     * Fetches prices from the OSRS Wiki API and generates a suggestion based on price spread.
     * This provides local suggestions without requiring the flippingcopilot.com server.
     */
    public void getSuggestionFromOsrsWikiAsync(Consumer<Suggestion> suggestionConsumer,
                                               Consumer<HttpResponseException> onFailure) {
        log.debug("Fetching prices from OSRS Wiki API");
        Request request = new Request.Builder()
                .url(OSRS_WIKI_PRICES_URL)
                .addHeader("Accept", "application/json")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.warn("call to OSRS Wiki prices API failed", e);
                clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR)));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful()) {
                        log.warn("OSRS Wiki prices API failed with http status code {}", response.code());
                        clientThread.invoke(() -> onFailure.accept(new HttpResponseException(response.code(), extractErrorMessage(response))));
                        return;
                    }
                    
                    String body = response.body() == null ? "" : response.body().string();
                    JsonObject jsonResponse = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject data = jsonResponse.getAsJsonObject("data");
                    
                    if (data == null) {
                        log.warn("No data in OSRS Wiki prices response");
                        clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, "No price data available")));
                        return;
                    }
                    
                    // Find items with good price spread (potential for profit)
                    Suggestion suggestion = findBestFlipSuggestion(data);
                    if (suggestion == null) {
                        // Return a wait suggestion if no good flip found
                        suggestion = new Suggestion("wait", 0, 0, 0, 0, "", 0, "No good flips found", null, null, null, null, false, -1);
                    }
                    
                    clientThread.invoke(() -> suggestionConsumer.accept(suggestion));
                    
                } catch (Exception e) {
                    log.warn("error reading/parsing OSRS Wiki prices response", e);
                    clientThread.invoke(() -> onFailure.accept(new HttpResponseException(-1, UNKNOWN_ERROR)));
                }
            }
        });
    }
    
    /**
     * Analyzes price data and finds the best flip suggestion based on price spread.
     * This is a simple heuristic that looks for items with good high-low spread.
     */
    private Suggestion findBestFlipSuggestion(JsonObject priceData) {
        List<MarketCandidate> candidates = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : priceData.entrySet()) {
            int itemId;
            try {
                itemId = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            JsonObject itemData = entry.getValue().getAsJsonObject();
            int lowPrice = getInt(itemData, "low", 0);
            int highPrice = getInt(itemData, "high", 0);
            int lowVolume = getInt(itemData, "lowTime", 0);
            int highVolume = getInt(itemData, "highTime", 0);
            if (lowPrice <= 0 || highPrice <= lowPrice) {
                continue;
            }

            int spread = highPrice - lowPrice;
            double margin = (double) spread / lowPrice;
            if (margin < 0.015 || spread < 10) {
                continue;
            }
            int volumeSignal = Math.max(1, Math.min(lowVolume, highVolume));
            double quickScore = spread * Math.log(volumeSignal + 1.0);
            candidates.add(new MarketCandidate(itemId, lowPrice, highPrice, spread, margin, volumeSignal, quickScore));
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingDouble((MarketCandidate c) -> c.quickScore).reversed());
        List<MarketCandidate> scanned = candidates.subList(0, Math.min(DETAIL_SCAN_LIMIT, candidates.size()));

        MarketCandidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MarketCandidate candidate : scanned) {
            ItemDetail detail = fetchItemDetail(candidate.itemId);
            if (detail == null || detail.name == null || detail.name.isBlank()) {
                continue;
            }
            double combinedScore = scoreCandidateWithDetail(candidate, detail);
            if (combinedScore > bestScore) {
                bestScore = combinedScore;
                best = candidate.withDetail(detail);
            }
        }

        if (best == null || best.detail == null) {
            return null;
        }

        int quantity = Math.max(1, computeTargetQuantity(best));
        int expectedProfitPerItem = Math.max(1, best.spread - (int) Math.ceil(best.highPrice * 0.01));
        String message = String.format(
                "Buy %s for %,d gp x %,d. Suggested sell: %,d gp x %,d. Signals: %s",
                best.detail.name,
                best.lowPrice,
                quantity,
                best.highPrice,
                quantity,
                buildSignalSummary(best.detail)
        );

        return new Suggestion(
                "buy",
                0,
                best.itemId,
                best.lowPrice,
                quantity,
                best.detail.name,
                0,
                message,
                (double) expectedProfitPerItem * quantity,
                null,
                null,
                null,
                false,
                -1
        );
    }

    private int computeTargetQuantity(MarketCandidate best) {
        int limitFromVolume = Math.max(1, best.volumeSignal / 8);
        if (best.lowPrice <= 1_000) {
            return Math.min(500, limitFromVolume);
        }
        if (best.lowPrice <= 50_000) {
            return Math.min(100, limitFromVolume);
        }
        return Math.min(20, limitFromVolume);
    }

    private double scoreCandidateWithDetail(MarketCandidate candidate, ItemDetail detail) {
        double score = candidate.quickScore;
        score += candidate.margin * 2_000;
        score += detail.day30ChangePct * 8;
        score += detail.day90ChangePct * 5;
        score += detail.day180ChangePct * 3;
        if ("positive".equalsIgnoreCase(detail.todayTrend)) {
            score += 50;
        } else if ("negative".equalsIgnoreCase(detail.todayTrend)) {
            score -= 50;
        }
        if ("negative".equalsIgnoreCase(detail.currentTrend)) {
            score -= 25;
        }
        return score;
    }

    private String buildSignalSummary(ItemDetail detail) {
        return String.format(
                "today=%s, 30d=%+.1f%%, 90d=%+.1f%%, 180d=%+.1f%%",
                detail.todayTrend,
                detail.day30ChangePct,
                detail.day90ChangePct,
                detail.day180ChangePct
        );
    }

    private ItemDetail fetchItemDetail(int itemId) {
        synchronized (itemDetailCache) {
            CachedItemDetail cached = itemDetailCache.get(itemId);
            if (cached != null && System.currentTimeMillis() - cached.loadedAtMs < ITEM_DETAIL_CACHE_MS) {
                return cached.detail;
            }
        }

        Request request = new Request.Builder()
                .url(OSRS_ITEM_DETAIL_URL + itemId)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", OSRS_WIKI_USER_AGENT)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            JsonObject jsonResponse = JsonParser.parseString(body).getAsJsonObject();
            JsonObject item = jsonResponse.getAsJsonObject("item");
            if (item == null) {
                return null;
            }
            ItemDetail detail = ItemDetail.from(item);
            synchronized (itemDetailCache) {
                itemDetailCache.put(itemId, new CachedItemDetail(detail, System.currentTimeMillis()));
            }
            return detail;
        } catch (Exception e) {
            log.debug("Failed to fetch item detail for item {}", itemId, e);
            return null;
        }
    }

    private int getInt(JsonObject object, String key, int defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static final class CachedItemDetail {
        private final ItemDetail detail;
        private final long loadedAtMs;

        private CachedItemDetail(ItemDetail detail, long loadedAtMs) {
            this.detail = detail;
            this.loadedAtMs = loadedAtMs;
        }
    }

    private static final class ItemDetail {
        private final String name;
        private final String currentTrend;
        private final String todayTrend;
        private final double day30ChangePct;
        private final double day90ChangePct;
        private final double day180ChangePct;

        private ItemDetail(String name, String currentTrend, String todayTrend, double day30ChangePct, double day90ChangePct, double day180ChangePct) {
            this.name = name;
            this.currentTrend = currentTrend;
            this.todayTrend = todayTrend;
            this.day30ChangePct = day30ChangePct;
            this.day90ChangePct = day90ChangePct;
            this.day180ChangePct = day180ChangePct;
        }

        private static ItemDetail from(JsonObject item) {
            return new ItemDetail(
                    getString(item, "name", "Unknown item"),
                    getString(item.getAsJsonObject("current"), "trend", "neutral"),
                    getString(item.getAsJsonObject("today"), "trend", "neutral"),
                    parsePercent(item.getAsJsonObject("day30"), "change"),
                    parsePercent(item.getAsJsonObject("day90"), "change"),
                    parsePercent(item.getAsJsonObject("day180"), "change")
            );
        }

        private static double parsePercent(JsonObject object, String key) {
            String value = getString(object, key, "0");
            String cleaned = value.replace("%", "").replace("+", "").trim();
            try {
                return Double.parseDouble(cleaned);
            } catch (NumberFormatException ex) {
                return 0;
            }
        }

        private static String getString(JsonObject object, String key, String defaultValue) {
            if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
                return defaultValue;
            }
            try {
                return object.get(key).getAsString();
            } catch (Exception ex) {
                return defaultValue;
            }
        }
    }

    private static final class MarketCandidate {
        private final int itemId;
        private final int lowPrice;
        private final int highPrice;
        private final int spread;
        private final double margin;
        private final int volumeSignal;
        private final double quickScore;
        private ItemDetail detail;

        private MarketCandidate(int itemId, int lowPrice, int highPrice, int spread, double margin, int volumeSignal, double quickScore) {
            this.itemId = itemId;
            this.lowPrice = lowPrice;
            this.highPrice = highPrice;
            this.spread = spread;
            this.margin = margin;
            this.volumeSignal = volumeSignal;
            this.quickScore = quickScore;
        }

        private MarketCandidate withDetail(ItemDetail detail) {
            this.detail = detail;
            return this;
        }
    }
}
