package com.github.liyibo1110.caffeine.cache;

/**
 * 用于表示写入顺序队列的双端队列实现
 * 注意这个属于侵入式（intrusive）实现，即自己本身不存全部Node，而是让Node自己保存其前后节点
 * @author liyibo
 * @date 2026-01-14 14:48
 */
final class WriteOrderDeque<E extends WriteOrderDeque.WriteOrder<E>> extends AbstractLinkedDeque<E> {
    @Override
    public boolean contains(Object obj) {
        return obj instanceof WriteOrderDeque.WriteOrder<?>
                && this.contains((WriteOrderDeque.WriteOrder<?>)obj);
    }

    /**
     * 快速即可判断，无需遍历
     */
    boolean contains(WriteOrder<?> e) {
        return e.getPreviousInWriteOrder() != null
                || e.getNextInWriteOrder() != null
                || e == this.first;
    }

    @Override
    public boolean remove(Object obj) {
        return obj instanceof WriteOrder<?>
                && this.remove((E)obj);
    }

    boolean remove(E e) {
        if(this.contains(e)) {
            this.unlink(e);
            return true;
        }
        return false;
    }

    @Override
    public E getPrevious(E e) {
        return e.getPreviousInWriteOrder();
    }

    @Override
    public void setPrevious(E e, E prev) {
        e.setPreviousInWriteOrder(prev);
    }

    @Override
    public E getNext(E e) {
        return e.getNextInWriteOrder();
    }

    @Override
    public void setNext(E e, E next) {
        e.setNextInWriteOrder(next);
    }

    /**
     * 能够存到Deque里面的元素
     * 注意泛型使用了自类型（self type），目的是限制实现类的泛型，只能用该实现类自己，不能传入其它类型，
     * 例如Node实现了WriteOrder，实现的方法参数和返回值都会只能是Node本身
     */
    interface WriteOrder<T extends WriteOrder<T>> {
        /**
         * 返回上一个元素
         */
        T getPreviousInWriteOrder();

        /**
         * 设置上一个元素
         */
        void setPreviousInWriteOrder(T prev);

        /**
         * 返回下一个元素
         */
        T getNextInWriteOrder();

        /**
         * 设置下一个元素
         */
        void setNextInWriteOrder(T next);
    }
}
