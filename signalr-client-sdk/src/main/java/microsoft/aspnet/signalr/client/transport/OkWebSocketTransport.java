package microsoft.aspnet.signalr.client.transport;

import org.java_websocket.exceptions.InvalidDataException;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import microsoft.aspnet.signalr.client.ConnectionBase;
import microsoft.aspnet.signalr.client.Logger;
import microsoft.aspnet.signalr.client.SignalRFuture;
import microsoft.aspnet.signalr.client.UpdateableCancellableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class OkWebSocketTransport extends HttpClientTransport {

    private WebSocket mWebsocket;
    OkHttpClient mClient = new OkHttpClient();
    private UpdateableCancellableFuture<Void> mConnectionFuture;
    private StringBuffer mBuffer;


    public OkWebSocketTransport(Logger logger) {
        super(logger);
    }

    @Override
    public String getName() {
        return "webSockets";
    }

    @Override
    public boolean supportKeepAlive() {
        return true;
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
                mConnectionFuture.setResult(null);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                callback.onData(text);
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                callback.onData(bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                super.onClosing(webSocket, code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                mWebsocket.cancel();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                mWebsocket.cancel();
                mConnectionFuture.triggerError(t);
            }
        });

        return mConnectionFuture;
    }
}
