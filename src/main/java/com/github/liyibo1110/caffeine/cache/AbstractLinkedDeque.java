package com.github.liyibo1110.caffeine.cache;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.NoSuchElementException;

/**
 * LinkedDeque的抽象模板实现
 * @author liyibo
 * @date 2026-01-14 10:51
 */
abstract class AbstractLinkedDeque<E> extends AbstractCollection<E> implements LinkedDeque<E> {
    /** 指向首元素 */
    E first;
    /** 指向尾元素 */
    E last;

    /**
     * 将指定元素连接到首元素之前，成为新的首元素
     */
    void linkFirst(final E e) {
        final E f = this.first;
        this.first = e;

        if(f == null) { // 队列为空，首尾都设为同一个元素
            this.last = e;
        }else {
            this.setPrevious(f, e);
            this.setNext(e, f);
        }
    }

    /**
     * 将指定元素连接到尾元素之后，成为新的尾元素
     */
    void linkLast(final E e) {
        final E l = this.last;
        this.last = e;

        if(l == null) { // 队列为空，首尾都设为同一个元素
            this.first = e;
        }else {
            this.setNext(l, e);
            this.setPrevious(e, l);
        }
    }

    /**
     * 移除并返回非null的首元素
     */
    E unlinkFirst() {
        final E f = this.first;
        final E next = this.getNext(f);
        this.setNext(f, null);  // 首元素脱钩

        this.first = next;
        if(next == null)    // 队列为空了
            this.last = null;
        else
            setPrevious(next, null);
        return f;
    }

    /**
     * 移除并返回非null的尾元素
     */
    E unlinkLast() {
        final E l = this.last;
        final E prev = this.getPrevious(l);
        this.setPrevious(l, null);
        this.last = prev;
        if(prev == null)
            this.first = null;
        else
            this.setNext(prev, null);
        return l;
    }

    /**
     * 移除指定元素
     */
    void unlink(E e) {
        final E prev = this.getPrevious(e);
        final E next = this.getNext(e);

        if(prev == null) {  // 说明e是首元素
            this.first = next;
        }else {
            this.setNext(prev, next);
            this.setPrevious(e, null);
        }

        if(next == null)    // 说明e是尾元素
            this.last = prev;
        else {
            this.setPrevious(next, prev);
            this.setNext(e, null);
        }
    }

    @Override
    public boolean isEmpty() {
        return this.first == null;
    }

    void checkNotEmpty() {
        if(this.isEmpty())
            throw new NoSuchElementException();
    }

    /**
     * 注意时间复杂度不是O(1)，需要遍历
     */
    @Override
    public int size() {
        int size = 0;
        for(E e = this.first; e != null; e = this.getNext(e))
            size++;
        return size;
    }

    @Override
    public void clear() {
        for(E e = this.first; e != null;) {
            E next = this.getNext(e);
            this.setPrevious(e, null);
            this.setNext(e, null);
            e = next;
        }
        this.first = null;
        this.last = null;
    }

    @Override
    public abstract boolean contains(Object obj);

    @Override
    public boolean isFirst(E e) {
        return e != null && e == this.first;
    }

    @Override
    public boolean isLast(E e) {
        return e != null && e == this.last;
    }

    @Override
    public void moveToFront(E e) {
        if(e != this.first) {
            this.unlink(e);
            this.linkFirst(e);
        }
    }

    @Override
    public void moveToBack(E e) {
        if(e != this.last) {
            this.unlink(e);
            this.linkLast(e);
        }
    }

    @Override
    public E peek() {
        return this.peekFirst();
    }

    @Override
    public E peekFirst() {
        return this.first;
    }

    @Override
    public E peekLast() {
        return this.last;
    }

    @Override
    public E getFirst() {
        this.checkNotEmpty();
        return this.peekFirst();
    }

    @Override
    public E getLast() {
        this.checkNotEmpty();
        return this.peekLast();
    }

    @Override
    public E element() {
        return this.getFirst();
    }

    @Override
    public boolean offer(E e) {
        return this.offerLast(e);
    }

    @Override
    public boolean offerFirst(E e) {
        if(this.contains(e))
            return false;
        this.linkFirst(e);
        return true;
    }

    @Override
    public boolean offerLast(E e) {
        if(this.contains(e))
            return false;
        this.linkLast(e);
        return true;
    }

    @Override
    public boolean add(E e) {
        return this.offerLast(e);
    }

    @Override
    public void addFirst(E e) {
        if(!this.offerFirst(e))
            throw new IllegalArgumentException();
    }

    @Override
    public void addLast(E e) {
        if(!this.offerLast(e))
            throw new IllegalArgumentException();
    }

    @Override
    public E poll() {
        return this.pollFirst();
    }

    @Override
    public E pollFirst() {
        return this.isEmpty() ? null : this.unlinkFirst();
    }

    @Override
    public E pollLast() {
        return this.isEmpty() ? null : this.unlinkLast();
    }

    @Override
    public E remove() {
        return this.removeFirst();
    }

    @Override
    public E removeFirst() {
        this.checkNotEmpty();
        return this.pollFirst();
    }

    @Override
    public boolean removeFirstOccurrence(Object obj) {
        return this.remove(obj);
    }

    @Override
    public E removeLast() {
        this.checkNotEmpty();
        return this.pollLast();
    }

    @Override
    public boolean removeLastOccurrence(Object obj) {
        return this.remove(obj);
    }

    @Override
    public boolean removeAll(Collection<?> col) {
        boolean modified = false;
        for(Object obj : col)
            modified |= this.remove(obj);
        return modified;
    }

    @Override
    public void push(E e) {
        this.addFirst(e);
    }

    @Override
    public E pop() {
        return this.removeFirst();
    }

    @Override
    public PeekingIterator<E> iterator() {
        return new AbstractLinkedIterator(this.first) {
            @Override
            E computeNext() {
                return AbstractLinkedDeque.this.getNext(this.cursor);
            }
        };
    }

    @Override
    public PeekingIterator<E> descendingIterator() {
        return new AbstractLinkedIterator(this.last) {
            @Override
            E computeNext() {
                return AbstractLinkedDeque.this.getPrevious(this.cursor);
            }
        };
    }

    abstract class AbstractLinkedIterator implements PeekingIterator<E> {
        E previous;
        /** 当前指向元素 */
        E cursor;

        AbstractLinkedIterator(E start) {
            cursor = start;
        }

        @Override
        public boolean hasNext() {
            return this.cursor != null;
        }

        @Override
        public E peek() {
            return this.cursor;
        }

        @Override
        public E next() {
            if(!this.hasNext())
                throw new NoSuchElementException();
            this.previous = this.cursor;
            this.cursor = this.computeNext();
            return this.previous;
        }

        /**
         * 计算并返回下一个元素（通过基于cursor + 特定自定义规则）
         */
        abstract E computeNext();

        @Override
        public void remove() {
            if(this.previous == null)   // 要符合迭代器语义，至少要调用1次next才可以调用remove，
                throw new IllegalStateException();
            AbstractLinkedDeque.this.remove(this.previous);
            this.previous = null;
        }
    }
}
