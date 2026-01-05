package com.github.liyibo1110.caffeine.test;

/**
 * 测试代码专用类
 * @author liyibo
 * @date 2026-01-05 15:32
 */
public class Tester {

    static int ceilingPowerOfTwo(int x) {
        // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
        return 1 << -Integer.numberOfLeadingZeros(x - 1);
    }

    public static void main(String[] args) {
        System.out.println(ceilingPowerOfTwo(1));
    }
}
