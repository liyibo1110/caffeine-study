package com.github.liyibo1110.caffeine.cache;

/**
 * 一个用于估算元素在特定时间窗口内流行度的概率多重集。
 * 元素的最大频率限制为15（4个bit大小），并且有一个老化过程会定期将所有元素的流行度减半。
 * 本身是一个准入/淘汰策略的记忆体，用小内存来估算：某个key最近一段时间被访问的有多频繁，给W-TinyLFU做决策用。
 * cache的驱逐策略是：维护一个“最近窗口”的近似访问频率统计，
 * 每个key有一个计数器，最多统计到15，统计到一定量后会把所有计数衰减一半，让旧热点慢慢淡出
 * @author liyibo
 * @date 2026-01-15 17:08
 */
final class FrequencySketch<E> {
    static final long[] SEED = { // A mixture of seeds from FNV-1a, CityHash, and Murmur3
            0xc3a5c85c97cb3127L, 0xb492b66fbe98f273L, 0x9ae16a3b2f90404fL, 0xcbf29ce484222325L};

    /** 配置右移运算符做频率衰减用 */
    static final long RESET_MASK = 0x7777777777777777L;

    /** 用来取出每个4bit的最低位 */
    static final long ONE_MASK = 0x1111111111111111L;

    /** 衰减周期的阈值，即有效地increment累积执行次数达到了sampleSize，就要触发reset衰减了 */
    int sampleSize;
    int tableMask;
    /** 计数器底层，每个long元素代表16个计数器（因为16 * 4bit（统计1个key的频率，最多到15） = 64bit） */
    long[] table;
    int size;

    public FrequencySketch() {}

    /**
     * table扩容，长度应为cache最大容量（向上再取2次幂）
     * 扩容会丢弃计数历史（和ArrayList不一样）
     */
    public void ensureCapacity(long maximumSize) {
        Caffeine.requireArgument(maximumSize >= 0);
        int maximum = (int)Math.min(maximumSize, Integer.MAX_VALUE >>> 1);
        if(this.table != null && this.table.length >= maximum)  // 空间够
            return;
        // 扩展
        this.table = new long[(maximum == 0) ? 1 : Caffeine.ceilingPowerOfTwo(maximum)];
        this.tableMask = Math.max(0, this.table.length - 1);
        this.sampleSize = (maximumSize == 0) ? 10 : (10 * maximum);
        if(this.sampleSize <= 0)
            this.sampleSize = Integer.MAX_VALUE;
        this.size = 0;
    }

    public boolean isNotInitialized() {
        return this.table == null;
    }

    /**
     * 估算给定元素的出现次数，最多不超过15
     */
    public int frequency(E e) {
        if(this.isNotInitialized())
            return 0;

        int hash = this.spread(e.hashCode());
        int start = (hash & 3) << 2;    // 单个long里面的找特定计数器（0 ~ 15）
        int frequency = Integer.MAX_VALUE;
        for(int i = 0; i < 4; i++) {
            int index = this.indexOf(hash, i);  // 找到元素所在的table index
            int count = (int)((this.table[index] >>> ((start + i) << 2)) & 0xfL);
            frequency = Math.min(frequency, count);
        }
        return frequency;
    }

    /**
     * 如果指定元素的频率不超过15，则增加
     * 当整体频率超过阈值，所有元素将执行衰减
     */
    public void increment(E e) {
        if(this.isNotInitialized())
            return;

        int hash = spread(e.hashCode());
        int start = (hash & 3) << 2;    // 单个long里面的找特定计数器（0 ~ 15）

        // 找table index
        int index0 = this.indexOf(hash, 0);
        int index1 = this.indexOf(hash, 1);
        int index2 = this.indexOf(hash, 2);
        int index3 = this.indexOf(hash, 3);

        boolean added = this.incrementAt(index0, start);
        added |= this.incrementAt(index1, start + 1);
        added |= this.incrementAt(index2, start + 2);
        added |= this.incrementAt(index3, start + 3);

        if(added && (++this.size == this.sampleSize))
            this.reset();
    }

    /**
     * 对指定元素进行统计自增
     * @param i table index
     * @param j counter index
     */
    boolean incrementAt(int i, int j) {
        int offset = j << 2;
        long mask = 0xfL << offset;
        if((this.table[i] & mask) != mask) {
            this.table[i] += (1L << offset);
            return true;
        }
        return false;
    }

    void reset() {
        int count = 0;
        for(int i = 0; i < this.table.length; i++) {
            count += Long.bitCount(this.table[i] & ONE_MASK);
            this.table[i] = (this.table[i] >>> 1) & RESET_MASK;
        }
        this.size = (this.size >>> 1) - (count >>> 2);
    }

    /**
     * 返回指定depth的table下标
     * @param item 元素的hash值
     * @param i 计数器depth
     * @return table里对应的下标（每个都有16个计数器）
     */
    int indexOf(int item, int i) {
        long hash = (item + SEED[i]) * SEED[i];
        hash += (hash >>> 32);
        return ((int) hash) & tableMask;
    }

    /**
     * 对给定hashCode应用补充的hash函数，防止使用质量较低的hash函数
     * @param x 原始hashCode
     */
    int spread(int x) {
        x = ((x >>> 16) ^ x) * 0x45d9f3b;
        x = ((x >>> 16) ^ x) * 0x45d9f3b;
        return (x >>> 16) ^ x;
    }
}
