package com.anachat.chatsdk.internal.network;

import android.content.Context;

import com.anachat.chatsdk.internal.database.MessageRepository;
import com.anachat.chatsdk.internal.database.PreferencesManager;
import com.anachat.chatsdk.internal.model.MessageResponse;
import com.anachat.chatsdk.internal.model.inputdata.FcmToken;
import com.anachat.chatsdk.internal.utils.NFChatUtils;
import com.anachat.chatsdk.internal.utils.concurrent.ApiExecutor;
import com.anachat.chatsdk.internal.utils.concurrent.ApiExecutorFactory;
import com.anachat.chatsdk.internal.utils.constants.NetworkConstants;
import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLConnection;
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
            FcmToken fcmToken = new FcmToken(NFChatUtils.getUUID(context),
                    token,
                    "ANDROID",
                    PreferencesManager.getsInstance(context).getBusinessId());
            String body = new Gson().toJson(fcmToken);
            HTTPTransport httpTransport = new AndroidHTTPTransport();
            Request request = new POSTRequest(Method.POST,
                    PreferencesManager.getsInstance(context).getBaseUrl()
                            + "/fcm/devices", body, getHeaders(),
                    NetworkConstants.CONNECT_TIMEOUT);
            Response response = httpTransport.makeRequest(request);
            if (response.status >= 200 && response.status < 300) {
                try {
                    JSONObject jsonObject = new JSONObject(response.responseString);
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
        if (messageResponse.isFileUpload()) {
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
        apiExecutor.runAsync(new Runnable() {
            @Override
            public void run() {
                String file = messageResponse.getData().getContent().getInput().
                        getMedia().get(0).getPreviewUrl();
                File screenshotFile = new File(file);
                Map<String, String> body = new HashMap<>();
                body.put("file", file);
                HTTPTransport httpTransport = new AndroidHTTPTransport();
                Request request = new UploadRequest(Method.POST,
                        PreferencesManager.getsInstance(context).getBaseUrl() + "/files",
                        body, getMimeType(screenshotFile.getPath()), getFileHeaders(),
                        NetworkConstants.UPLOAD_CONNECT_TIMEOUT);
//                Request request = new POSTRequest(Method.POST,
//                        "https://chat-alpha.withfloats.com/fcm/devices", body, getHeaders(), 5000);
                Response response = httpTransport.makeRequest(request);
            }
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
        ArrayList headers = new ArrayList();
        headers.add(new KeyValuePair("Connection", "Keep-Alive"));
        headers.add(new KeyValuePair("Content-Type", "multipart/form-data;boundary=*****"));
        return headers;
    }

    private static List<KeyValuePair> getCommonHeaders() {
//        String userAgent = String.format(Locale.ENGLISH, "Nf-%s/%s/%s", new Object[]{this.device.getPlatformName(), this.device.getSDKVersion(), this.device.getOSVersion()});
//        String acceptLangHead = String.format(Locale.ENGLISH, "%s;q=1.0", new Object[]{this.localeProviderDM.getAcceptLanguageHeader()});
//        String xHSVHead = String.format(Locale.ENGLISH, "Helpshift-%s/%s", new Object[]{this.device.getPlatformName(), this.device.getSDKVersion()});
        ArrayList headers = new ArrayList();
//        headers.add(new KeyValuePair("User-Agent", userAgent));
//        headers.add(new KeyValuePair("Accept-Language", acceptLangHead));
//        headers.add(new KeyValuePair("Accept-Encoding", "gzip"));
//        headers.add(new KeyValuePair("X-HS-V", xHSVHead));
        return headers;
    }

}