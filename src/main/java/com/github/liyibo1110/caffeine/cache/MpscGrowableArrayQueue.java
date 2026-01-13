package com.github.liyibo1110.caffeine.cache;

import java.util.AbstractQueue;

/**
 * 一个MPSC（多生产者单消费者）数组队列，初始容量为initialCapacity，并以初始大小的链接块增长到maxCapacity。
 * 队列仅在当前缓冲区已满时才增长，并且在调整大小时不会复制元素，而是在旧缓冲区中存储一个指向新缓冲区的链接，以便消费者跟随。
 * @author liyibo
 * @date 2026-01-13 00:18
 */
public class MpscGrowableArrayQueue<E> {

    abstract class BaseMpscLinkedArrayQueuePad1<E> extends AbstractQueue<E> {
        byte p000, p001, p002, p003, p004, p005, p006, p007;
        byte p008, p009, p010, p011, p012, p013, p014, p015;
        byte p016, p017, p018, p019, p020, p021, p022, p023;
        byte p024, p025, p026, p027, p028, p029, p030, p031;
        byte p032, p033, p034, p035, p036, p037, p038, p039;
        byte p040, p041, p042, p043, p044, p045, p046, p047;
        byte p048, p049, p050, p051, p052, p053, p054, p055;
        byte p056, p057, p058, p059, p060, p061, p062, p063;
        byte p064, p065, p066, p067, p068, p069, p070, p071;
        byte p072, p073, p074, p075, p076, p077, p078, p079;
        byte p080, p081, p082, p083, p084, p085, p086, p087;
        byte p088, p089, p090, p091, p092, p093, p094, p095;
        byte p096, p097, p098, p099, p100, p101, p102, p103;
        byte p104, p105, p106, p107, p108, p109, p110, p111;
        byte p112, p113, p114, p115, p116, p117, p118, p119;
    }

    abstract class BaseMpscLinkedArrayQueueProducerFields<E> extends BaseMpscLinkedArrayQueuePad1<E> {
        /** 生产者的下标 */
        protected long producerIndex;
    }
}
