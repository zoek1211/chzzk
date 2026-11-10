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

    private SignalingClient(URI uri) {
        this.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri, new Collector()).join();
    }

    /** 접속한다. 서버가 핸드셰이크를 거부하면 예외가 난다. */
    static SignalingClient connect(String uri) {
        return new SignalingClient(URI.create(uri));
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
            closed = true;
            return null;
        }
    }
}
