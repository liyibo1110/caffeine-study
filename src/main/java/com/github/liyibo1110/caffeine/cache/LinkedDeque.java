package com.github.liyibo1110.caffeine.cache;

import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 基于链表的双端队列扩展，没有容量限制，同时不保证线程安全
 * @author liyibo
 * @date 2026-01-14 00:27
 */
public interface LinkedDeque<E> extends Deque<E> {

    /**
     * 给定实例是否等于队首元素
     */
    boolean isFirst(E e);

    /**
     * 给定实例是否等于队尾元素
     */
    boolean isLast(E e);

    /**
     * 给定实例移至队首
     */
    void moveToFront(E e);

    /**
     * 给定实例移至队尾
     */
    void moveToBack(E e);

    /**
     * 返回给定实例的前一个元素
     */
    E getPrevious(E e);

    /**
     * 设置给定元素的前驱元素
     */
    void setPrevious(E e, E prev);

    /**
     * 返回给定实例的后一个元素
     */
    E getNext(E e);

    /**
     * 设置给定元素的后置元素
     */
    void setNext(E e, E next);

    interface PeekingIterator<E> extends Iterator<E> {
        /**
         * 返回迭代中的下一个元素，但不向前迭代
         */
        E peek();

        /**
         * 返回合并2个迭代器的具体实现
         * 效果是先迭代第1个，再迭代第2个
         */
        static <E> PeekingIterator<E> concat(PeekingIterator<E> first, PeekingIterator<E> second) {
            return new PeekingIterator<E>() {
                @Override
                public boolean hasNext() {
                    return first.hasNext() || second.hasNext();
                }

                @Override
                public E next() {
                    if(first.hasNext())
                        return first.next();
                    else if(second.hasNext())
                        return second.next();
                    throw new NoSuchElementException();
                }

                @Override
                public E peek() {
                    return first.hasNext() ? first.peek() : second.peek();
                }
            };
        }

        /**
         * 返回同时迭代2个迭代器，并返回其中较大元素值的具体实现
         */
        static <E> PeekingIterator<E> comparing(PeekingIterator<E> first, PeekingIterator<E> second, Comparator<E> comparator) {
            return new PeekingIterator<>() {
                @Override
                public boolean hasNext() {
                    return first.hasNext() || second.hasNext();
                }

                @Override
                public E next() {
                    if(!first.hasNext())
                        return second.next();
                    else if(!second.hasNext())
                        return first.next();

                    E o1 = first.peek();
                    E o2 = second.peek();
                    boolean greaterOrEqual = (comparator.compare(o1, o2) >= 0);
                    return greaterOrEqual ? first.next() : second.next();
                }
                @Override
                public E peek() {
                    if(!first.hasNext())
                        return second.peek();
                    else if(!second.hasNext())
                        return first.peek();

                    E o1 = first.peek();
                    E o2 = second.peek();
                    boolean greaterOrEqual = (comparator.compare(o1, o2) >= 0);
                    return greaterOrEqual ? first.peek() : second.peek();
                }
            };
        }
    }
}
