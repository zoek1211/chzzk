package kr.devcourse.chzzk;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 테스트용 WebSocket 클라이언트. 받은 JSON 메시지를 순서대로 모아 둔다. */
final class SignalingClient implements AutoCloseable {

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final WebSocket socket;
    private volatile boolean closed;
    private volatile int closeCode = -1;

    private SignalingClient(URI uri, String cookie) {
        var builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        this.socket = builder.buildAsync(uri, new Collector()).join();
    }

    /** 세션 쿠키 없이(비로그인) 접속한다. 서버가 핸드셰이크를 거부하면 예외가 난다. */
    static SignalingClient connect(String uri) {
        return new SignalingClient(URI.create(uri), null);
    }

    /** 주어진 Cookie 헤더(로그인 세션)를 싣고 접속한다. 서버가 핸드셰이크를 거부하면 예외가 난다. */
    static SignalingClient connect(String uri, String cookie) {
        return new SignalingClient(URI.create(uri), cookie);
    }

    /** 방송자 접속 주소. */
    static String broadcasterUri(int port, String liveId) {
        return "ws://localhost:" + port + "/ws/live/" + liveId + "/broadcaster";
    }

    /** 그 회원의 로그인 세션으로 방송자로 접속하고, 서버의 접속 확인(authenticated)까지 기다린다. 거부되면 예외가 난다. */
    static SignalingClient broadcaster(int port, String liveId, TestLogin.Http member) throws InterruptedException {
        SignalingClient client = connect(broadcasterUri(port, liveId), member.cookie());
        String type = client.next("$.type");
        if (!"authenticated".equals(type)) {
            throw new AssertionError("방송자 접속 확인을 받지 못했습니다: " + type);
        }
        return client;
    }

    /** 접속 시도가 핸드셰이크에서 거부되면 그 HTTP 상태 코드, 접속되면(곧바로 닫는다) -1. */
    static int rejectedStatus(java.util.concurrent.Callable<SignalingClient> attempt) throws Exception {
        try {
            attempt.call().close();
            return -1;
        } catch (java.util.concurrent.CompletionException | AssertionError e) {
            Throwable cause = e;
            while (cause != null && !(cause instanceof java.net.http.WebSocketHandshakeException)) {
                cause = cause.getCause();
            }
            if (cause == null) {
                throw e;
            }
            return ((java.net.http.WebSocketHandshakeException) cause).getResponse().statusCode();
        }
    }

    void send(String json) {
        socket.sendText(json, true).join();
    }

    /** 다음 메시지를 기다린다. 없으면 null. */
    String next() throws InterruptedException {
        return messages.poll(3, TimeUnit.SECONDS);
    }

    /** 다음 메시지의 JSON 경로 값을 읽는다. */
    <T> T next(String path) throws InterruptedException {
        String message = next();
        if (message == null) {
            throw new AssertionError("메시지를 받지 못했습니다");
        }
        return JsonPath.read(message, path);
    }

    /** 짧게 기다려도 메시지가 없는지. */
    boolean receivesNothing() throws InterruptedException {
        return messages.poll(300, TimeUnit.MILLISECONDS) == null;
    }

    boolean isClosed() {
        return closed;
    }

    /** 서버가 보낸 닫힘 상태 코드. 아직 닫히지 않았으면 -1. */
    int closeCode() {
        return closeCode;
    }

    @Override
    public void close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

    private final class Collector implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            closed = true;
            return null;
        }
    }
}
