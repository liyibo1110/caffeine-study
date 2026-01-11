package com.github.liyibo1110.caffeine.cache;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine实例的规范构建器，主要功能是能支持纯字符串格式的解析并构建过程
 * @author liyibo
 * @date 2026-01-10 14:43
 */
public final class CaffeineSpec {
    static final String SPLIT_OPTIONS = ",";
    static final String SPLIT_KEY_VALUE = "=";

    final String specification;

    int initialCapacity = Caffeine.UNSET_INT;
    long maximumWeight = Caffeine.UNSET_INT;
    long maximumSize = Caffeine.UNSET_INT;
    boolean recordStats;

    Caffeine.Strength keyStrength;
    Caffeine.Strength valueStrength;
    Duration expireAfterWrite;
    Duration expireAfterAccess;
    Duration refreshAfterWrite;

    private CaffeineSpec(String specification) {
        this.specification = Objects.requireNonNull(specification);
    }

    /**
     * 以自身的配置，来构建Caffeine实例
     */
    Caffeine<Object, Object> toBuilder() {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if(this.initialCapacity != Caffeine.UNSET_INT)
            builder.initialCapacity(this.initialCapacity);
        if(this.maximumSize != Caffeine.UNSET_INT)
            builder.maximumSize(this.maximumSize);
        if(this.maximumWeight != Caffeine.UNSET_INT)
            builder.maximumWeight(this.maximumWeight);
        if(keyStrength != null) {
            Caffeine.requireState(keyStrength == Caffeine.Strength.WEAK);
            builder.weakKeys();
        }
        if(valueStrength != null) {
            if(valueStrength == Caffeine.Strength.WEAK)
                builder.weakValues();
            else if (valueStrength == Caffeine.Strength.SOFT)
                builder.softValues();
            else
                throw new IllegalStateException();
        }
        if(expireAfterWrite != null)
            builder.expireAfterWrite(expireAfterWrite);
        if(expireAfterAccess != null)
            builder.expireAfterAccess(expireAfterAccess);
        if(refreshAfterWrite != null)
            builder.refreshAfterWrite(refreshAfterWrite);
        if(recordStats)
            builder.recordStats();
        return builder;
    }

    /**
     * 根据给定的配置字符串，构建CaffeineSpec实例
     */
    public static CaffeineSpec parse(String specification) {
        CaffeineSpec spec = new CaffeineSpec(specification);
        for(String option : specification.split(SPLIT_OPTIONS))
            spec.parseOption(option.trim());
        return spec;
    }

    /**
     * 解析每一个配置（即每个逗号分隔出来的部分）
     */
    void parseOption(String option) {
        if(option.isEmpty())
            return;
        String[] keyAndValue = option.split(SPLIT_KEY_VALUE);
        Caffeine.requireArgument(keyAndValue.length <= 2,
                "key-value pair %s with more than one equals sign", option);

        String key = keyAndValue[0].trim();
        String value = (keyAndValue.length == 1) ? null : keyAndValue[1].trim();
        this.configure(key, value);
    }

    /**
     * 加载某个配置
     */
    void configure(String key, String value) {
        switch(key) {
            case "initialCapacity":
                this.initialCapacity(key, value);
                return;
            case "maximumSize":
                this.maximumSize(key, value);
                return;
            case "maximumWeight":
                this.maximumWeight(key, value);
                return;
            case "weakKeys":
                this.weakKeys(value);
                return;
            case "weakValues":
                this.valueStrength(key, value, Caffeine.Strength.WEAK);
                return;
            case "softValues":
                this.valueStrength(key, value, Caffeine.Strength.SOFT);
                return;
            case "expireAfterAccess":
                this.expireAfterAccess(key, value);
                return;
            case "expireAfterWrite":
                this.expireAfterWrite(key, value);
                return;
            case "refreshAfterWrite":
                this.refreshAfterWrite(key, value);
                return;
            case "recordStats":
                this.recordStats(value);
                return;
            default:
                throw new IllegalArgumentException("Unknown key " + key);
        }
    }

    void initialCapacity(String key, String value) {
        Caffeine.requireArgument(this.initialCapacity == Caffeine.UNSET_INT,
                "initial capacity was already set to %,d", this.initialCapacity);
        this.initialCapacity = parseInt(key, value);
    }

    void maximumSize(String key, @Nullable String value) {
        Caffeine.requireArgument(this.maximumSize == Caffeine.UNSET_INT,
                "maximum size was already set to %,d", this.maximumSize);
        Caffeine.requireArgument(this.maximumWeight == Caffeine.UNSET_INT,
                "maximum weight was already set to %,d", this.maximumWeight);
        this.maximumSize = parseLong(key, value);
    }

    void maximumWeight(String key, @Nullable String value) {
        Caffeine.requireArgument(this.maximumWeight == Caffeine.UNSET_INT,
                "maximum weight was already set to %,d", this.maximumWeight);
        Caffeine.requireArgument(this.maximumSize == Caffeine.UNSET_INT,
                "maximum size was already set to %,d", this.maximumSize);
        this.maximumWeight = parseLong(key, value);
    }

    void weakKeys(String value) {
        Caffeine.requireArgument(value == null, "weak keys does not take a value");
        Caffeine.requireArgument(this.keyStrength == null, "weak keys was already set");
        this.keyStrength = Caffeine.Strength.WEAK;
    }

    void valueStrength(String key, String value, Caffeine.Strength strength) {
        Caffeine.requireArgument(value == null, "%s does not take a value", key);
        Caffeine.requireArgument(this.valueStrength == null, "%s was already set to %s", key, this.valueStrength);
        this.valueStrength = strength;
    }

    void expireAfterAccess(String key, String value) {
        Caffeine.requireArgument(this.expireAfterAccess == null, "expireAfterAccess was already set");
        this.expireAfterAccess = parseDuration(key, value);
    }

    void expireAfterWrite(String key, String value) {
        Caffeine.requireArgument(this.expireAfterWrite == null, "expireAfterWrite was already set");
        this.expireAfterWrite = parseDuration(key, value);
    }

    void refreshAfterWrite(String key, String value) {
        Caffeine.requireArgument(this.refreshAfterWrite == null, "refreshAfterWrite was already set");
        this.refreshAfterWrite = parseDuration(key, value);
    }

    void recordStats(@Nullable String value) {
        Caffeine.requireArgument(value == null, "record stats does not take a value");
        Caffeine.requireArgument(!this.recordStats, "record stats was already set");
        this.recordStats = true;
    }

    /**
     * 将value转换成int
     */
    static int parseInt(String key, String value) {
        Caffeine.requireArgument(value != null && !value.isEmpty(), "value of key %s was omitted", key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("key %s value was set to %s, must be an integer", key, value), e);
        }
    }

    /**
     * 将value转换成long
     */
    static long parseLong(String key, String value) {
        Caffeine.requireArgument(value != null && !value.isEmpty(), "value of key %s was omitted", key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("key %s value was set to %s, must be an long", key, value), e);
        }
    }

    /**
     * 将value转换成Duration实例
     */
    static Duration parseDuration(String key, String value) {
        Caffeine.requireArgument(value != null && !value.isEmpty(), "value of key %s was omitted", key);
        boolean isIsoFormat = value.contains("p") || value.contains("P");
        if(isIsoFormat) {
            Duration duration = Duration.parse(value);
            Caffeine.requireArgument(!duration.isNegative(),
                    "key %s invalid format; was %s, but the duration cannot be negative", key, value);
            return duration;
        }

        long duration = parseLong(key, value.substring(0, value.length() - 1));
        TimeUnit unit = parseTimeUnit(key, value);
        return Duration.ofNanos(unit.toNanos(duration));
    }

    /**
     * 将value转换成TimeUnit实例
     */
    static TimeUnit parseTimeUnit(String key, String value) {
        Caffeine.requireArgument((value != null) && !value.isEmpty(), "value of key %s omitted", key);
        char lastChar = Character.toLowerCase(value.charAt(value.length() - 1));
        switch(lastChar) {
            case 'd':
                return TimeUnit.DAYS;
            case 'h':
                return TimeUnit.HOURS;
            case 'm':
                return TimeUnit.MINUTES;
            case 's':
                return TimeUnit.SECONDS;
            default:
                throw new IllegalArgumentException(String.format(
                        "key %s invalid format; was %s, must end with one of [dDhHmMsS]", key, value));
        }
    }

    public String toParsableString() {
        return this.specification;
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj)
            return true;
        else if (!(obj instanceof CaffeineSpec))
            return false;
        CaffeineSpec spec = (CaffeineSpec)obj;
        return Objects.equals(this.refreshAfterWrite, spec.refreshAfterWrite)
                && Objects.equals(this.expireAfterAccess, spec.expireAfterAccess)
                && Objects.equals(this.expireAfterWrite, spec.expireAfterWrite)
                && (this.initialCapacity == spec.initialCapacity)
                && (this.maximumWeight == spec.maximumWeight)
                && (this.valueStrength == spec.valueStrength)
                && (this.keyStrength == spec.keyStrength)
                && (this.maximumSize == spec.maximumSize)
                && (this.recordStats == spec.recordStats);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            this.initialCapacity, this.maximumSize, this.maximumWeight, this.keyStrength, this.valueStrength,
                this.recordStats, this.expireAfterWrite, this.expireAfterAccess, this.refreshAfterWrite);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + '{' + this.toParsableString() + '}';
    }
}
