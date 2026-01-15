package com.github.liyibo1110.caffeine.cache;

/**
 * 用于表示访问顺序队列的双端队列实现
 * 注意这个属于侵入式（intrusive）实现，即自己本身不存全部Node，而是让Node自己保存其前后节点
 * @author liyibo
 * @date 2026-01-14 14:22
 */
final class AccessOrderDeque<E extends AccessOrderDeque.AccessOrder<E>> extends AbstractLinkedDeque<E> {
    @Override
    public boolean contains(Object obj) {
        return obj instanceof AccessOrderDeque.AccessOrder<?>
                && this.contains((AccessOrderDeque.AccessOrder<?>)obj);
    }

    /**
     * 快速即可判断，无需遍历
     */
    boolean contains(AccessOrder<?> e) {
        return e.getPreviousInAccessOrder() != null
                || e.getNextInAccessOrder() != null
                || e == this.first;
    }

    @Override
    public boolean remove(Object obj) {
        return obj instanceof AccessOrder<?>
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
        return e.getPreviousInAccessOrder();
    }

    @Override
    public void setPrevious(E e, E prev) {
        e.setPreviousInAccessOrder(prev);
    }

    @Override
    public E getNext(E e) {
        return e.getNextInAccessOrder();
    }

    @Override
    public void setNext(E e, E next) {
        e.setNextInAccessOrder(next);
    }

    /**
     * 能够存到Deque里面的元素
     * 注意泛型使用了自类型（self type），目的是限制实现类的泛型，只能用该实现类自己，不能传入其它类型，
     * 例如Node实现了AccessOrder，实现的方法参数和返回值都会只能是Node本身
     */
    interface AccessOrder<T extends AccessOrder<T>> {
        /**
         * 返回上一个元素
         */
        T getPreviousInAccessOrder();

        /**
         * 设置上一个元素
         */
        void setPreviousInAccessOrder(T prev);

        /**
         * 返回下一个元素
         */
        T getNextInAccessOrder();

        /**
         * 设置下一个元素
         */
        void setNextInAccessOrder(T next);
    }
}
