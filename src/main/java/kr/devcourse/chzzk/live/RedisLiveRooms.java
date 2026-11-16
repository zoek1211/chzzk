package kr.devcourse.chzzk.live;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 클러스터 모드의 방 상태: Redis. 키·채널은 모두 {@code chzzk.cluster.redis-prefix}(기본 {@code chzzk:}) 아래에 둔다.
 *
 * <pre>
 * {p}instance:{instanceId}                "1"                      이 인스턴스가 살아 있다는 표시. TTL, 주기 갱신. 정상 종료 때 지운다
 * {p}live:{id}:presence                   "{instanceId}|{sessionId}"  방송자 접속 기록. SET NX PX(TTL), 주기 갱신(없어졌으면 되살림). 끊기면 지운다
 * {p}live:{id}:viewers                    hash viewerId → instanceId  시청자 명단. 정원 검사는 Lua 로 원자적으로 하고, 살아 있지 않은 인스턴스의 시청자는 세지 않고 지운다
 * {p}live:{id}:presence-absent-since      Redis 시각(epoch millis)    접속 기록이 없어진 것을 처음 본 때. 기록이 있으면 지우고, 자체 TTL 이 있다
 * {p}channel:instance:{instanceId}        {"to":sessionId,"message":{...}}  그 인스턴스의 세션에 전할 메시지(pub/sub)
 * {p}channel:live-ended                   liveId                   모든 인스턴스가 받아 자기 연결에 ended 를 보내고 닫는다
 * </pre>
 *
 * 종료 판정은 연결 끊김이 아니라 주기 검사가 한다: 방송 중인데 방송자 접속 기록이 {@code chzzk.live.broadcaster-join-timeout}보다
 * 오래 없는 라이브를 종료한다. "얼마나 오래 없었나"의 판정과 선점은 Redis 시각({@code TIME})을 쓰는 Lua 한 번으로 해서,
 * 인스턴스 시계가 달라도 되고 한 인스턴스만 종료를 맡는다. Lua 스크립트는 단일 Redis를 전제로 한다(ADR 0004).
 */
@Component
@ConditionalOnProperty(name = "chzzk.cluster.enabled", havingValue = "true")
class RedisLiveRooms implements LiveRooms, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisLiveRooms.class);

    /**
     * 시청자 정원 검사. 단일 Redis 전제(ADR 0004): 인스턴스 표시 키를 ARGV 접두어로 동적으로 만들어 읽으므로 KEYS 에 다 올리지 못한다.
     */
    private static final DefaultRedisScript<Long> ADMIT_VIEWER = new DefaultRedisScript<>("""
            local all = redis.call('HGETALL', KEYS[1])
            local count = 0
            for i = 1, #all, 2 do
              if redis.call('EXISTS', ARGV[3] .. all[i + 1]) == 1 then
                count = count + 1
              else
                redis.call('HDEL', KEYS[1], all[i])
              end
            end
            if count >= tonumber(ARGV[1]) then return 0 end
            redis.call('HSET', KEYS[1], ARGV[2], ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    /** 접속 기록 갱신. 내 것이면 연장(1), 없으면 되살림(2), 다른 방송자의 것이면 아무것도 안 하고 0. */
    private static final DefaultRedisScript<Long> RENEW_OR_RECREATE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if not current then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 2
            end
            return 0
            """, Long.class);
    /**
     * KEYS[1] 접속 기록, KEYS[2] 없어진 때. ARGV[1] 접속 유예(ms), ARGV[2] 없어진 때 기록의 TTL(ms).
     * 기록이 있으면 없어진 때를 지우고 0. 없으면 Redis 시각으로: 처음 보면 기록하고 0, 유예를 넘었으면 선점(지움)하고 1.
     */
    private static final DefaultRedisScript<Long> CLAIM_ABSENT_TOO_LONG = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('DEL', KEYS[2])
              return 0
            end
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            local since = redis.call('GET', KEYS[2])
            if not since then
              redis.call('SET', KEYS[2], now, 'PX', ARGV[2])
              return 0
            end
            if now - tonumber(since) > tonumber(ARGV[1]) then
              redis.call('DEL', KEYS[2])
              return 1
            end
            return 0
            """, Long.class);

    private static final long TAKEN_BY_OTHER = 0L;

    /** 접속 기록의 값: 방송자 연결을 쥔 인스턴스와 그 세션. */
    private record PresenceRecord(String instanceId, String sessionId) {

        private static final char SEPARATOR = '|';

        String encode() {
            return instanceId + SEPARATOR + sessionId;
        }

        static PresenceRecord parse(String value) {
            int split = value.lastIndexOf(SEPARATOR);
            if (split < 0) {
                throw new IllegalArgumentException("접속 기록 형식이 아닙니다: " + value);
            }
            return new PresenceRecord(value.substring(0, split), value.substring(split + 1));
        }
    }

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper json;
    private final LiveBroadcastService live;
    private final int viewerCapacity;
    private final Duration joinTimeout;
    private final String instanceId;
    private final String prefix;
    private final Duration ttl;
    /** 이 인스턴스가 쥔 방송자 연결의 접속 기록 중 갱신해야 할 것: liveId → sessionId. */
    private final Map<Long, String> renewedPresences = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private RedisMessageListenerContainer container;
    private volatile Connections connections;

    RedisLiveRooms(
            StringRedisTemplate redis,
            RedisConnectionFactory connectionFactory,
            ObjectMapper json,
            LiveBroadcastService live,
            @Value("${chzzk.live.viewer-capacity:5}") int viewerCapacity,
            @Value("${chzzk.live.broadcaster-join-timeout:30s}") Duration joinTimeout,
            @Value("${chzzk.instance-id:local}") String instanceId,
            @Value("${chzzk.cluster.redis-prefix:chzzk:}") String prefix,
            @Value("${chzzk.cluster.record-ttl:6s}") Duration ttl) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        this.json = json;
        this.live = live;
        this.viewerCapacity = viewerCapacity;
        this.joinTimeout = joinTimeout;
        this.instanceId = instanceId;
        this.prefix = prefix;
        this.ttl = ttl;
    }

    private String instanceKey(String id) {
        return prefix + "instance:" + id;
    }

    private String presenceKey(Long liveId) {
        return prefix + "live:" + liveId + ":presence";
    }

    private String viewersKey(Long liveId) {
        return prefix + "live:" + liveId + ":viewers";
    }

    private String presenceAbsentSinceKey(Long liveId) {
        return prefix + "live:" + liveId + ":presence-absent-since";
    }

    private String instanceChannel(String id) {
        return prefix + "channel:instance:" + id;
    }

    private String endedChannel() {
        return prefix + "channel:live-ended";
    }

    @Override
    public void attach(Connections connections) {
        this.connections = connections;
    }

    /** 웹 서버가 요청을 받기 전에 시작하고, 웹 서버가 내려간 뒤에 멈춘다(그 사이 연결 정리는 {@link #stopping()}이 한다). */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 2048;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        heartbeat();
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.addMessageListener((message, pattern) -> onInstanceMessage(body(message.getBody())),
                new ChannelTopic(instanceChannel(instanceId)));
        container.addMessageListener((message, pattern) -> onLiveEndedMessage(body(message.getBody())),
                new ChannelTopic(endedChannel()));
        container.start();
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "live-rooms-redis");
            thread.setDaemon(true);
            return thread;
        });
        long renewMillis = Math.max(100, ttl.toMillis() / 3);
        scheduler.scheduleWithFixedDelay(this::heartbeat, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        long checkMillis = Math.max(100, Math.min(1000, joinTimeout.toMillis() / 2));
        scheduler.scheduleWithFixedDelay(this::checkBroadcasters, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try {
            if (container != null) {
                container.destroy();
            }
        } catch (Exception e) {
            log.debug("Redis 구독을 닫지 못했습니다", e);
        }
        container = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    private static String body(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 이 인스턴스가 살아 있다는 표시와 이 인스턴스가 쥔 방송자 접속 기록을 갱신한다. */
    private void heartbeat() {
        try {
            redis.opsForValue().set(instanceKey(instanceId), "1", ttl);
            renewedPresences.forEach(this::renewPresence);
        } catch (RuntimeException e) {
            log.warn("Redis 접속 기록 갱신에 실패했습니다", e);
        }
    }

    /**
     * 연결을 쥐고 있는 동안은 기록이 만료됐어도 되살린다. 그 사이 다른 인스턴스의 방송자가 기록을 쥐었다면
     * 이쪽이 중복 방송자이므로 연결을 닫는다. 방송은 끝나지 않는다.
     */
    private void renewPresence(Long liveId, String sessionId) {
        Long result = redis.execute(RENEW_OR_RECREATE, List.of(presenceKey(liveId)),
                new PresenceRecord(instanceId, sessionId).encode(), String.valueOf(ttl.toMillis()));
        if (result != null && result == TAKEN_BY_OTHER && renewedPresences.remove(liveId, sessionId)) {
            connections.closeDuplicateBroadcaster(sessionId);
        }
    }

    @Override
    public boolean admitBroadcaster(Long liveId, String sessionId) {
        boolean admitted = Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(presenceKey(liveId), new PresenceRecord(instanceId, sessionId).encode(), ttl));
        if (admitted) {
            renewedPresences.put(liveId, sessionId);
        }
        return admitted;
    }

    /** 접속 기록만 지운다. 방송 종료는 주기 검사가 유예 시간이 지난 뒤에만 한다. */
    @Override
    public void broadcasterGone(Long liveId, String sessionId) {
        renewedPresences.remove(liveId, sessionId);
        redis.execute(RELEASE, List.of(presenceKey(liveId)), new PresenceRecord(instanceId, sessionId).encode());
    }

    @Override
    public boolean admitViewer(Long liveId, String viewerId) {
        Long admitted = redis.execute(ADMIT_VIEWER, List.of(viewersKey(liveId)),
                String.valueOf(viewerCapacity), viewerId, instanceKey(""), instanceId);
        return admitted != null && admitted == 1L;
    }

    @Override
    public boolean viewerGone(Long liveId, String viewerId) {
        Long removed = redis.opsForHash().delete(viewersKey(liveId), viewerId);
        return removed != null && removed > 0;
    }

    @Override
    public Collection<String> viewerIds(Long liveId) {
        return redis.<String, String>opsForHash().entries(viewersKey(liveId)).entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(redis.hasKey(instanceKey(entry.getValue()))))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void toBroadcaster(Long liveId, ObjectNode message) {
        String value = redis.opsForValue().get(presenceKey(liveId));
        if (value == null) {
            return;
        }
        PresenceRecord presence = PresenceRecord.parse(value);
        deliver(presence.instanceId(), presence.sessionId(), message);
    }

    @Override
    public void toViewer(Long liveId, String viewerId, ObjectNode message) {
        Object instance = redis.opsForHash().get(viewersKey(liveId), viewerId);
        if (instance != null) {
            deliver(instance.toString(), viewerId, message);
        }
    }

    private void deliver(String instance, String sessionId, ObjectNode message) {
        if (instance.equals(instanceId)) {
            connections.deliver(sessionId, message);
            return;
        }
        ObjectNode envelope = json.createObjectNode().put("to", sessionId);
        envelope.set("message", message);
        redis.convertAndSend(instanceChannel(instance), envelope.toString());
    }

    private void onInstanceMessage(String payload) {
        try {
            JsonNode envelope = json.readTree(payload);
            if (envelope.get("message") instanceof ObjectNode message) {
                connections.deliver(envelope.get("to").asString(), message);
            }
        } catch (RuntimeException e) {
            log.debug("잘못된 인스턴스 메시지를 무시합니다", e);
        }
    }

    private void onLiveEndedMessage(String payload) {
        try {
            Long liveId = Long.valueOf(payload);
            renewedPresences.remove(liveId);
            connections.endConnectionsOf(liveId);
        } catch (RuntimeException e) {
            log.debug("잘못된 종료 알림을 무시합니다", e);
        }
    }

    /** 라이브가 시작되었다: 지금부터 접속 유예 시간 안에 방송자가 접속해야 한다(없어진 때를 지금으로 기록한다). */
    @Override
    public void liveStarted(Long liveId) {
        claimIfAbsentTooLong(liveId);
    }

    @Override
    public void liveEnded(Long liveId) {
        redis.delete(List.of(presenceKey(liveId), viewersKey(liveId), presenceAbsentSinceKey(liveId)));
        redis.convertAndSend(endedChannel(), String.valueOf(liveId));
    }

    /** 방송 중인데 방송자 접속 기록이 유예 시간보다 오래 없는 라이브를 종료한다. */
    private void checkBroadcasters() {
        try {
            for (Long liveId : live.onAirIds()) {
                try {
                    if (claimIfAbsentTooLong(liveId)) {
                        live.endByDisconnect(liveId);
                    }
                } catch (RuntimeException e) {
                    log.warn("방송자 접속 유예 시간 검사에 실패했습니다", e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("방송 중인 라이브 조회에 실패했습니다", e);
        }
    }

    /**
     * 접속 기록이 유예 시간보다 오래 없었다면 그 판정을 선점해 true(한 인스턴스만 받는다). 기록이 있으면 false.
     * 방송 중으로 확인된 라이브에만 쓰고 "없어진 때" 키에 TTL 이 있어, 종료 직후 검사와 겹쳐도 남지 않는다.
     */
    private boolean claimIfAbsentTooLong(Long liveId) {
        long absentSinceTtl = joinTimeout.toMillis() * 2 + 10_000;
        Long claimed = redis.execute(CLAIM_ABSENT_TOO_LONG,
                List.of(presenceKey(liveId), presenceAbsentSinceKey(liveId)),
                String.valueOf(joinTimeout.toMillis()), String.valueOf(absentSinceTtl));
        return claimed != null && claimed == 1L;
    }

    @Override
    public boolean livesOutliveInstance() {
        return true;
    }

    /** 정상 종료: 살아 있다는 표시를 지워, 이 인스턴스의 시청자가 정원을 차지하지 않게 한다. */
    @Override
    public void stopping() {
        stop();
        try {
            redis.delete(instanceKey(instanceId));
        } catch (RuntimeException e) {
            log.debug("인스턴스 표시를 지우지 못했습니다", e);
        }
    }
}
