package microsoft.aspnet.signalr.client.transport;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import microsoft.aspnet.signalr.client.ConnectionBase;
import microsoft.aspnet.signalr.client.LogLevel;
import microsoft.aspnet.signalr.client.Logger;
import microsoft.aspnet.signalr.client.SignalRFuture;
import microsoft.aspnet.signalr.client.UpdateableCancellableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class OkWebSocketTransport implements ClientTransport {

    private WebSocket mWebsocket;
    OkHttpClient mClient = new OkHttpClient();
    private UpdateableCancellableFuture<Void> mConnectionFuture;
    private Logger mLogger;
    private boolean mStartedAbort;
    SignalRFuture<Void> mAbortFuture = new SignalRFuture<>();


    public OkWebSocketTransport(Logger logger) {
        mLogger = logger;
    }

    @Override
    public String getName() {
        return "webSockets";
    }

    @Override
    public boolean supportKeepAlive() {
        return true;
    }

    public SignalRFuture<NegotiationResponse> negotiate(final ConnectionBase connection) {
        this.log("Start the negotiation with the server", LogLevel.Information);
        String url = connection.getUrl() + "negotiate" + TransportHelper.getNegotiateQueryString(connection);
        Request.Builder builder = new Request.Builder();
        builder.url(url);
        for (String key : connection.getHeaders().keySet()) {
            builder.addHeader(key, connection.getHeaders().get(key));
        }
        builder.method("GET", null);
        final SignalRFuture<NegotiationResponse> negotiationFuture = new SignalRFuture<>();
        this.log("Execute the request", LogLevel.Verbose);
        try {
            Response response = mClient.newCall(builder.build()).execute();
            OkWebSocketTransport.this.log("Response received", LogLevel.Verbose);
            ResponseBody responseBody = response.body();
            String negotiationContent = "";
            if (responseBody != null) {
                negotiationContent = responseBody.string();
            }
            OkWebSocketTransport.this.log("Trigger onSuccess with negotiation data: " + negotiationContent, LogLevel.Verbose);
            negotiationFuture.setResult(new NegotiationResponse(negotiationContent, connection.getJsonParser()));
        } catch (IOException e) {
            OkWebSocketTransport.this.log(e);
            negotiationFuture.triggerError(new NegotiationException("There was a problem in the negotiation with the server", e));
        }
        return negotiationFuture;
    }

    @Override
    public SignalRFuture<Void> start(ConnectionBase connection, ConnectionType connectionType, final DataResultCallback callback) {
        final String connectionString = connectionType == ConnectionType.InitialConnection ? "connect" : "reconnect";

        final String transport = getName();
        final String connectionToken = connection.getConnectionToken();
        final String messageId = connection.getMessageId() != null ? connection.getMessageId() : "";
        final String groupsToken = connection.getGroupsToken() != null ? connection.getGroupsToken() : "";
        final String connectionData = connection.getConnectionData() != null ? connection.getConnectionData() : "";
        boolean isSsl = false;
        String url = null;
        try {
            url = connection.getUrl() + connectionString + '?'
                    + "connectionData=" + URLEncoder.encode(connectionData, "UTF-8")
                    + "&connectionToken=" + URLEncoder.encode(connectionToken, "UTF-8");
            if (!groupsToken.isEmpty()) {
                url += "&groupsToken=" + URLEncoder.encode(groupsToken, "UTF-8");
            }
            if (!messageId.isEmpty()) {
                url += "&messageId=" + URLEncoder.encode(messageId, "UTF-8");
            }
            if (!transport.isEmpty()) {
                url += "&transport=" + URLEncoder.encode(transport, "UTF-8");
            }

            if (connection.getQueryString() != null) {
                url += "&" + connection.getQueryString();
            }

            if (url.startsWith("https://")) {
                isSsl = true;
                url = url.replace("https://", "wss://");
            } else if (url.startsWith("http://")) {
                url = url.replace("http://", "ws://");
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        Request.Builder builder = new Request.Builder();
        builder.url(url);
        for (String key : connection.getHeaders().keySet()) {
            builder.addHeader(key, connection.getHeaders().get(key));
        }
        mConnectionFuture = new UpdateableCancellableFuture<Void>(null);

        mWebsocket = mClient.newWebSocket(builder.build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                log("Socket is open", LogLevel.Verbose);
                mConnectionFuture.setResult(null);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                log("Socket received data " + text, LogLevel.Verbose);
                callback.onData(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                String data =  bytes.utf8();
                log("Socket received data " + data, LogLevel.Verbose);
                callback.onData(data);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                super.onClosing(webSocket, code, reason);
                log("Socket is closing " + reason, LogLevel.Verbose);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                log("Socket is closed " + reason, LogLevel.Verbose);
                mWebsocket.close(0, null);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                mWebsocket.cancel();
                mConnectionFuture.triggerError(t);
                log("Socket failed to open ", LogLevel.Verbose);
            }
        });

        return mConnectionFuture;
    }

    @Override
    public SignalRFuture<Void> send(ConnectionBase connection, String data, final DataResultCallback callback) {
        mWebsocket.send(data);
        SignalRFuture<Void> future = new SignalRFuture<Void>();
        future.setResult(null);
        return future;
    }

    @Override
    public SignalRFuture<Void> abort(ConnectionBase connection) {
        synchronized (this) {
            if (!mStartedAbort) {
                log("Started aborting", LogLevel.Information);
                mStartedAbort = true;
                mAbortFuture = new SignalRFuture<>();
                try {
                    String url = connection.getUrl() + "abort" + TransportHelper.getSendQueryString(this, connection);

                    Request.Builder builder = new Request.Builder();
                    builder.url(url);
                    for (String key : connection.getHeaders().keySet()) {
                        builder.addHeader(key, connection.getHeaders().get(key));
                    }
                    log("Execute request", LogLevel.Verbose);


                    mClient.newCall(builder.build()).execute();
                    mAbortFuture.setResult(null);

                } catch (IOException e){
                    mAbortFuture.triggerError(e);
                }
            }
        }
        return mAbortFuture;
    }

    private void log(String message, LogLevel level) {
        this.mLogger.log(this.getName() + " - " + message, level);
    }

    private void log(Throwable error) {
        this.mLogger.log(this.getName() + " - Error: " + error.toString(), LogLevel.Critical);
    }
}
