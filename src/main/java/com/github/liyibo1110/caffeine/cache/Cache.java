package com.github.liyibo1110.caffeine.cache;

import com.github.liyibo1110.caffeine.cache.stats.CacheStats;
import com.google.errorprone.annotations.CompatibleWith;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 最基础的CacheAPI接口
 * 此接口实现需要线程安全
 * @author liyibo
 * @date 2026-01-06 16:14
 */
public interface Cache<K, V> {

    /**
     * 根据key返回对应的value，不存在则返回null
     */
    V getIfPresent(@CompatibleWith("K") Object key);

    /**
     * 根据key返回对应的value，不存在则调用function来生成value，并放入cache
     * function应简单且不要更新cache的其它条目
     */
    V get(K key, Function<? super K, ? extends V> function);

    /**
     * 根据keys返回key和value，value不存在则不会加到map中
     */
    Map<K, V> getAllPresent(Iterable<?> keys);

    default Map<K, V> getAll(Iterable<? extends K> keys,
                             Function<Iterable<? extends K>, Map<K, V>> function) {
        throw new UnsupportedOperationException();
    }

    /**
     * 如key存在，则用新value替换旧值
     */
    void put(K key, V value);

    void putAll(Map<? extends K, ? extends V> map);

    /**
     * 丢弃key对应的value
     */
    void invalidate(@CompatibleWith("K") Object key);

    void invalidateAll(Iterable<?> keys);

    void invalidateAll();

    /**
     * 返回cache中近似的条目数
     */
    long estimatedSize();

    /**
     * 返回cache的快照，所有统计信息初始化为0
     * 维护统计信息会带来性能损失，一些实现可能不会立即记录，或者根本不记录
     */
    CacheStats stats();

    /**
     * 将cache以线程安全形式返回，对映射所作的修改会直接影响cache
     */
    ConcurrentMap<K, V> asMap();

    /**
     * 执行cache所需的任何待处理的维护操作
     * 具体执行内容，由实现类自行决定
     */
    void cleanUp();

    /**
     * 返回对应的策略实例
     */
    Policy<K, V> policy();
}
