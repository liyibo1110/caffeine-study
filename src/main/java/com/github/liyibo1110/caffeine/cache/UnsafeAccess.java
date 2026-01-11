package com.github.liyibo1110.caffeine.cache;

import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/**
 * 封装了对Unsafe类的一些操作，用于执行低级不安全（相对）的操作
 * @author liyibo
 * @date 2026-01-10 01:26
 */
final class UnsafeAccess {
    static final String OPEN_JDK = "theUnsafe";
    static final String ANDROID = "THE_ONE";

    public static final Unsafe UNSAFE;
    private UnsafeAccess() {}

    static {
        try {
            UNSAFE = load(OPEN_JDK, ANDROID);
        } catch (Exception e) {
            throw new Error("Failed to load sun.misc.Unsafe", e);
        }
    }

    static Unsafe load(String openJdk, String android) throws NoSuchMethodException,
            InvocationTargetException, InstantiationException, IllegalAccessException {
        Field field;
        try {
            // 先尝试openJdk环境
            field = Unsafe.class.getDeclaredField(openJdk);
        } catch (NoSuchFieldException e) {
            try {
                // 再尝试Android环境
                field = Unsafe.class.getDeclaredField(android);
            } catch (NoSuchFieldException e2) {
                // 2个环境都没有，则自己构建
                Constructor<Unsafe> constructor = Unsafe.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        }
        // openJdk or Android
        field.setAccessible(true);
        return (Unsafe)field.get(null); // 如果是static的字段，传null即可
    }

    /**
     * 返回给定static字段的地址位置
     */
    public static long objectFieldOffset(Class<?> clazz, String fieldName) {
        try {
            return UNSAFE.objectFieldOffset(clazz.getDeclaredField(fieldName));
        } catch (NoSuchFieldException | SecurityException e) {
            throw new Error(e);
        }
    }
}
