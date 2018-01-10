package com.anachat.chatsdk.internal.network;

import android.content.Context;

import com.anachat.chatsdk.internal.database.MessageRepository;
import com.anachat.chatsdk.internal.database.PreferencesManager;
import com.anachat.chatsdk.internal.model.MessageResponse;
import com.anachat.chatsdk.internal.model.inputdata.FcmToken;
import com.anachat.chatsdk.internal.utils.ListenerManager;
import com.anachat.chatsdk.internal.utils.NFChatUtils;
import com.anachat.chatsdk.internal.utils.concurrent.ApiExecutor;
import com.anachat.chatsdk.internal.utils.concurrent.ApiExecutorFactory;
import com.anachat.chatsdk.internal.utils.constants.Constants;
import com.anachat.chatsdk.internal.utils.constants.NetworkConstants;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiCalls {

    public static void updateToken(final Context context, final String token,
                                   final MessageResponse messageResponse) {
        PreferencesManager.getsInstance(context).setFcmToken(token);
        PreferencesManager.getsInstance(context).setTokenSync(false);
        if (PreferencesManager.getsInstance(context).getBaseUrl().isEmpty()) return;
        if (!NFChatUtils.isNetworkConnected(context)) return;
        ApiExecutor apiExecutor = ApiExecutorFactory.getHandlerExecutor();
        apiExecutor.runAsync(() -> {
            FcmToken fcmToken = new FcmToken(
                    NFChatUtils.getUUID(context),
                    token,
                    "ANDROID",
                    PreferencesManager.getsInstance(context).getBusinessId(),
                    PreferencesManager.getsInstance(context).getUserName());
            String body = new Gson().toJson(fcmToken);
            HTTPTransport httpTransport = new AndroidHTTPTransport();
            Request request = new POSTRequest(Method.POST,
                    PreferencesManager.getsInstance(context).getBaseUrl()
                            + "/fcm/devices", body, getHeaders(),
                    NetworkConstants.CONNECT_TIMEOUT);
            Response response = httpTransport.makeRequest(request);
            if (response.status >= 200 && response.status < 300) {
                try {
                    String responseString = response.responseString;
                    JSONObject jsonObject = new JSONObject(responseString);
                    if (jsonObject.has("userId")) {
                        PreferencesManager.getsInstance(context).setUserName(
                                jsonObject.getString("userId"));
                        PreferencesManager.getsInstance(context).setTokenSync(true);
                        if (messageResponse != null) {
                            sendMessage(context, messageResponse);
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public static void sendMessage(final Context context, final MessageResponse messageResponse) {
        if (!NFChatUtils.isNetworkConnected(context)) return;
        if (!PreferencesManager.getsInstance(context).getTokenSync()) {
            updateToken(context,
                    PreferencesManager.getsInstance(context).getFcmToken(), messageResponse);
            return;
        }
        if (messageResponse.getData().isFileUpload()) {
            uploadFile(context, messageResponse);
            return;
        }
        ApiExecutor apiExecutor = ApiExecutorFactory.getHandlerExecutor();
        apiExecutor.runAsync(() -> {
            String body = new Gson().toJson(messageResponse);
            HTTPTransport httpTransport = new AndroidHTTPTransport();
            Request request = new POSTRequest(Method.POST,
                    PreferencesManager.getsInstance(context).getBaseUrl() + "/api", body, getHeaders(),
                    NetworkConstants.CONNECT_TIMEOUT);
            Response response = httpTransport.makeRequest(request);
            if (response.status >= 200 && response.status < 300) {
                try {
                    MessageResponse messageResponseServer = new Gson().
                            fromJson(response.responseString, MessageResponse.class);
                    messageResponseServer.getMessage().setMessageType(
                            messageResponseServer.getData().getType());
                    messageResponseServer.setTimestampToUpdate(
                            messageResponse.getMessage().getTimestamp());
                    messageResponseServer.getMessage().setSyncWithServer(true);
                    messageResponseServer.setOnlyUpdate(true);
                    MessageRepository.getInstance(context).
                            handleMessageResponse(messageResponseServer);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

    }

    public static void uploadFile(final Context context, final MessageResponse messageResponse) {
        ApiExecutor apiExecutor = ApiExecutorFactory.getHandlerExecutor();
        apiExecutor.runAsync(() -> {
            String file = messageResponse.getData().getContent().getInput().
                    getMedia().get(0).getPreviewUrl();
            File screenshotFile = new File(file);
            Map<String, String> body = new HashMap<>();
            body.put("file", file);
            HTTPTransport httpTransport = new AndroidHTTPTransport();
            Request request = new UploadRequest(Method.POST,
                    PreferencesManager.getsInstance(context).getBaseUrl() + "/files/",
                    body, getMimeType(screenshotFile.getPath()), getFileHeaders(),
                    NetworkConstants.UPLOAD_CONNECT_TIMEOUT);
            Response response = httpTransport.makeRequest(request);
            if (response.status >= 200 && response.status < 300) {
                try {
                    JSONObject jsonObject = new JSONObject(response.responseString);
                    if (jsonObject.has("links")) {
                        JSONArray url = jsonObject.getJSONArray("links");
                        if (url.length() > 0) {
                            String mediaUrl = url.getJSONObject(0).getString("href");
                            messageResponse.getData().getContent().getInput().
                                    getMedia().get(0).setPreviewUrl(mediaUrl);
                            messageResponse.getData().getContent().getInput().
                                    getMedia().get(0).setUrl(mediaUrl);
                            messageResponse.getData().setFileUpload(false);
                            MessageRepository.getInstance(context).updateMediaMessage
                                    (messageResponse.getData().getContent().getInput(),
                                            messageResponse.getMessage().getMessageInput()
                                                    .getInputTypeMedia().getId());
                            sendMessage(context, messageResponse);
                        }
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                } catch (SQLException e) {
                    e.printStackTrace();
                }

            }
        });

    }


    public static void fetchHistoryMessages(final Context context, Integer page, Integer size,
                                            long timestamp) {
        if (PreferencesManager.getsInstance(context).getBaseUrl().isEmpty()
                || !NFChatUtils.isNetworkConnected(context)
                || (PreferencesManager.getsInstance(context).getHistorySynced() && page != 0)) {
            if (page > 0)
                ListenerManager.getInstance().notifyHistoryLoaded(new ArrayList<>(), page);
            return;
        }
        ApiExecutor apiExecutor = ApiExecutorFactory.getHandlerExecutor();
        apiExecutor.runAsync(() -> {
            String urlparms = "?userId=" + PreferencesManager.
                    getsInstance(context).getUserName() + "&businessId=" +
                    PreferencesManager.getsInstance(context).getBusinessId() +
                    "&size=" + Constants.HISTORY_MESSAGES_LIMIT;
            if (timestamp == 0) {
                urlparms = urlparms + "&page=" + page;
            } else {
                urlparms = urlparms + "&lastMessageTimeStamp=" + timestamp;
            }
            HTTPTransport httpTransport = new AndroidHTTPTransport();
            Request request = new POSTRequest(Method.GET,
                    PreferencesManager.getsInstance(context).getBaseUrl()
                            + "/chatdata/messages" + urlparms, "", getCommonHeaders(),
                    NetworkConstants.CONNECT_TIMEOUT);
            Response response = null;
            try {
                response = httpTransport.makeRequest(request);
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (response != null && response.status >= 200 && response.status < 300) {
                try {
                    JSONObject jsonObject = new JSONObject(response.responseString);
                    if (jsonObject.has("content")) {
                        List<MessageResponse> messageResponses
                                = new
                                Gson().fromJson(jsonObject.get("content").toString(),
                                new TypeToken<List<MessageResponse>>() {
                                }.getType());
                        if (jsonObject.has("isLast") &&
                                jsonObject.getBoolean("isLast"))
                            PreferencesManager.getsInstance(context).setIsHistorySynced(true);
                        if (messageResponses != null && messageResponses.size() > 0) {
                            MessageRepository.getInstance(context)
                                    .saveHistoryMessages(messageResponses, size, page);
                            return;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            //TODO failing when single message added from get started and history loads
            ListenerManager.getInstance().notifyHistoryLoaded(new ArrayList<>(), page);
        });

    }

    private static List<KeyValuePair> getHeaders() {
        List headers = getCommonHeaders();
        headers.add(new KeyValuePair("Content-type", "application/json"));
        return headers;
    }

    private static String getMimeType(String url) {
        try {
            FileInputStream inputStream = new FileInputStream(url);
            String e;
            if ((e = URLConnection.guessContentTypeFromStream(inputStream)) == null) {
                e = URLConnection.guessContentTypeFromName(url);
            }

            inputStream.close();
            return e;
        } catch (IOException var4) {
            return null;
        }
    }

    private static List<KeyValuePair> getFileHeaders() {
        ArrayList<KeyValuePair> headers = new ArrayList<>();
        String boundary = "===" + System.currentTimeMillis() + "===";
        headers.add(new KeyValuePair("Connection", "Keep-Alive"));
        headers.add(new KeyValuePair("Content-Type", "multipart/form-data; boundary=" + boundary));
        headers.add(new KeyValuePair("Accept", "*/*"));
        return headers;
    }

    private static List<KeyValuePair> getCommonHeaders() {
        ArrayList headers = new ArrayList();
        return headers;
    }

}
