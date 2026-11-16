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
 * {p}instance:{instanceId}              "1"                      이 인스턴스가 살아 있다는 표시. TTL, 주기 갱신. 정상 종료 때 지운다
 * {p}live:{id}:broadcaster              "{instanceId}|{sessionId}"  방송자 접속 기록. SET NX PX(TTL), 주기 갱신. 끊기면 지운다
 * {p}live:{id}:viewers                  hash viewerId → instanceId  시청자 명단. 정원 검사는 Lua 로 원자적으로 하고, 살아 있지 않은 인스턴스의 시청자는 세지 않고 지운다
 * {p}live:{id}:broadcaster-seen         epoch millis             방송자 접속 기록이 마지막으로 있었던 때(라이브 시작 시각 포함)
 * {p}channel:instance:{instanceId}      {"to":sessionId,"message":{...}}  그 인스턴스의 세션에 전할 메시지(pub/sub)
 * {p}channel:live-ended                 liveId                   모든 인스턴스가 받아 자기 연결에 ended 를 보내고 닫는다
 * </pre>
 *
 * 종료 판정은 연결 끊김이 아니라 주기 검사가 한다: 방송 중인데 방송자 접속 기록이 {@code chzzk.live.broadcaster-join-timeout}보다
 * 오래 없는 라이브를 종료한다(모든 인스턴스가 검사하며 종료는 멱등).
 */
@Component
@ConditionalOnProperty(name = "chzzk.cluster.enabled", havingValue = "true")
class RedisLiveRooms implements LiveRooms {

    private static final Logger log = LoggerFactory.getLogger(RedisLiveRooms.class);

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
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper json;
    private final LiveBroadcastService live;
    private final int viewerCapacity;
    private final Duration joinTimeout;
    private final String instanceId;
    private final String prefix;
    private final Duration ttl;
    /** 이 인스턴스가 방송자로 들인 연결: liveId → sessionId. 접속 기록 갱신 대상. */
    private final Map<Long, String> myBroadcasters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "live-rooms-redis");
        thread.setDaemon(true);
        return thread;
    });
    private RedisMessageListenerContainer container;
    private volatile Local local;

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

    private String broadcasterKey(Long liveId) {
        return prefix + "live:" + liveId + ":broadcaster";
    }

    private String viewersKey(Long liveId) {
        return prefix + "live:" + liveId + ":viewers";
    }

    private String seenKey(Long liveId) {
        return prefix + "live:" + liveId + ":broadcaster-seen";
    }

    private String instanceChannel(String id) {
        return prefix + "channel:instance:" + id;
    }

    private String endedChannel() {
        return prefix + "channel:live-ended";
    }

    @Override
    public void attach(Local local) {
        this.local = local;
        heartbeat();
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.addMessageListener((message, pattern) -> onInstanceMessage(body(message.getBody())),
                new ChannelTopic(instanceChannel(instanceId)));
        container.addMessageListener((message, pattern) -> onLiveEndedMessage(body(message.getBody())),
                new ChannelTopic(endedChannel()));
        container.start();
        long renewMillis = Math.max(100, ttl.toMillis() / 3);
        scheduler.scheduleWithFixedDelay(this::heartbeat, renewMillis, renewMillis, TimeUnit.MILLISECONDS);
        long checkMillis = Math.max(100, Math.min(1000, joinTimeout.toMillis() / 2));
        scheduler.scheduleWithFixedDelay(this::checkBroadcasters, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
    }

    private static String body(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 이 인스턴스가 살아 있다는 표시와 이 인스턴스가 들인 방송자 접속 기록을 갱신한다. */
    private void heartbeat() {
        try {
            redis.opsForValue().set(instanceKey(instanceId), "1", ttl);
            myBroadcasters.forEach((liveId, sessionId) -> redis.execute(RENEW, List.of(broadcasterKey(liveId)),
                    record(sessionId), String.valueOf(ttl.toMillis())));
        } catch (RuntimeException e) {
            log.warn("Redis 접속 기록 갱신에 실패했습니다", e);
        }
    }

    private String record(String sessionId) {
        return instanceId + "|" + sessionId;
    }

    @Override
    public boolean admitBroadcaster(Long liveId, String sessionId) {
        boolean admitted = Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(broadcasterKey(liveId), record(sessionId), ttl));
        if (admitted) {
            myBroadcasters.put(liveId, sessionId);
            markSeen(liveId);
        }
        return admitted;
    }

    /** 접속 기록만 지운다. 방송 종료는 주기 검사가 유예 시간이 지난 뒤에만 한다. */
    @Override
    public void broadcasterGone(Long liveId, String sessionId) {
        myBroadcasters.remove(liveId, sessionId);
        redis.execute(RELEASE, List.of(broadcasterKey(liveId)), record(sessionId));
        markSeen(liveId);
    }

    private void markSeen(Long liveId) {
        redis.opsForValue().set(seenKey(liveId), String.valueOf(System.currentTimeMillis()));
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
        String record = redis.opsForValue().get(broadcasterKey(liveId));
        if (record == null) {
            return;
        }
        int split = record.lastIndexOf('|');
        deliver(record.substring(0, split), record.substring(split + 1), message);
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
            local.deliver(sessionId, message);
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
                local.deliver(envelope.get("to").asString(), message);
            }
        } catch (RuntimeException e) {
            log.debug("잘못된 인스턴스 메시지를 무시합니다", e);
        }
    }

    private void onLiveEndedMessage(String payload) {
        try {
            Long liveId = Long.valueOf(payload);
            myBroadcasters.remove(liveId);
            local.endLocal(liveId);
        } catch (RuntimeException e) {
            log.debug("잘못된 종료 알림을 무시합니다", e);
        }
    }

    /** 라이브가 시작되었다: 지금부터 접속 유예 시간 안에 방송자가 접속해야 한다. */
    @Override
    public void liveStarted(Long liveId) {
        markSeen(liveId);
    }

    @Override
    public void liveEnded(Long liveId) {
        redis.delete(List.of(broadcasterKey(liveId), viewersKey(liveId), seenKey(liveId)));
        redis.convertAndSend(endedChannel(), String.valueOf(liveId));
    }

    /** 방송 중인데 방송자 접속 기록이 유예 시간보다 오래 없는 라이브를 종료한다. */
    private void checkBroadcasters() {
        try {
            for (Long liveId : live.onAirIds()) {
                try {
                    checkBroadcaster(liveId);
                } catch (RuntimeException e) {
                    log.warn("방송자 접속 유예 시간 검사에 실패했습니다", e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("방송 중인 라이브 조회에 실패했습니다", e);
        }
    }

    private void checkBroadcaster(Long liveId) {
        if (Boolean.TRUE.equals(redis.hasKey(broadcasterKey(liveId)))) {
            markSeen(liveId);
            return;
        }
        String seen = redis.opsForValue().get(seenKey(liveId));
        if (seen == null) {
            redis.opsForValue().setIfAbsent(seenKey(liveId), String.valueOf(System.currentTimeMillis()));
        } else if (System.currentTimeMillis() - Long.parseLong(seen) > joinTimeout.toMillis()) {
            live.endByDisconnect(liveId);
        }
    }

    @Override
    public boolean keepsLivesOnShutdown() {
        return true;
    }

    /** 정상 종료: 살아 있다는 표시를 지워, 이 인스턴스의 시청자가 정원을 차지하지 않게 한다. */
    @Override
    public void stopping() {
        scheduler.shutdownNow();
        try {
            redis.delete(instanceKey(instanceId));
        } catch (RuntimeException e) {
            log.debug("인스턴스 표시를 지우지 못했습니다", e);
        }
        destroyContainer();
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        destroyContainer();
    }

    private void destroyContainer() {
        try {
            if (container != null && container.isRunning()) {
                container.destroy();
            }
        } catch (Exception e) {
            log.debug("Redis 구독을 닫지 못했습니다", e);
        }
    }
}
