package io.jenkins.plugins.bouteiller.push;

import io.jenkins.plugins.bouteiller.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** A relay that decrypts what it receives with the test key, and answers what the test chooses. */
public final class FakeRelay implements Relay {
    /** A notification as the device would read it. */
    public record Received(Request request, Map<String, Object> event) {
        public String eventType() {
            return (String) event.get("eventType");
        }
    }

    private final byte[] key = PushEventTest.sequence(32);
    private final List<Received> received = new ArrayList<>();
    private volatile Function<Request, Outcome> answer = request -> Outcome.DELIVERED;

    public void answer(Function<Request, Outcome> answer) {
        this.answer = answer;
    }

    @Override
    public Result send(Request request) {
        try {
            String json = new String(EventCipher.decrypt(key, request.payload()), StandardCharsets.UTF_8);
            synchronized (this) {
                received.add(new Received(request, Json.parseObject(json)));
                notifyAll();
            }
        } catch (Exception e) {
            throw new AssertionError("The relay received an event it cannot decrypt", e);
        }
        return new Result(answer.apply(request), "fake");
    }

    public synchronized List<Received> received() {
        return List.copyOf(received);
    }

    /** Waits until {@code count} notifications arrived, at most 30 s. */
    public synchronized List<Received> await(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (received.size() < count) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("Expected " + count + " notifications, got " + received);
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return List.copyOf(received);
    }
}
