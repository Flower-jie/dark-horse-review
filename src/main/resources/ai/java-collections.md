# Java 集合框架：List、Map、Set 与遍历

## List 的两种常用实现

ArrayList 底层是 Object 数组，支持按下标随机访问，get 和 set 的时间复杂度是 O(1)；尾部追加元素均摊 O(1)，在中间插入或删除需要移动后续元素，复杂度是 O(n)。ArrayList 在第一次添加元素时才分配默认容量 10 的数组，扩容时新容量约为旧容量的 1.5 倍（oldCapacity + (oldCapacity >> 1)），并把旧数组复制到新数组，所以能预估大小时应使用带初始容量的构造方法，减少扩容复制。

LinkedList 底层是双向链表，按下标 get 需要从头或尾逐节点查找，复杂度是 O(n)；在已知节点位置插入、删除是 O(1)，适合频繁在头部或中间增删、而很少随机访问的场景。需要当队列或栈使用时，ArrayDeque 通常比 LinkedList 更快，因为它是循环数组实现、没有额外的节点对象开销。

## HashMap 的结构与哈希冲突

JDK 8 的 HashMap 是「数组 + 链表 + 红黑树」。默认初始容量 16，负载因子 0.75，扩容阈值等于容量乘以负载因子。下标通过 (n - 1) & hash 计算，其中 n 是数组长度，因此容量始终保持 2 的幂。为了让高位也参与运算，HashMap 会对 key 的 hashCode 做扰动：h ^ (h >>> 16)。

当多个 key 落到同一个桶时就是哈希冲突，冲突的节点以链表形式挂在桶上。JDK 8 中，链表长度达到 8 且数组长度不小于 64 时，链表会转换为红黑树，把最坏查找从 O(n) 降到 O(log n)；如果数组长度还小于 64，则优先扩容而不是树化。树中节点数量减少到 6 时退化为链表。扩容时容量翻倍，元素的新下标要么保持不变，要么是原下标加旧容量，因此不需要重新计算 hash。

JDK 8 的 HashMap 使用尾插法，JDK 7 使用头插法，后者在并发扩容时可能形成环形链表。但改成尾插只是消除了这个问题，HashMap 依然不是线程安全的，多线程读写必须使用 ConcurrentHashMap 或在外层加锁。ConcurrentHashMap 在 JDK 8 中用 CAS 加 synchronized 锁住桶的首节点，不允许 null 键和 null 值。

## equals 与 hashCode 的约定

作为 HashMap 的 key，必须正确重写 equals 和 hashCode：equals 相等的两个对象，hashCode 必须相等；hashCode 相等的对象，equals 不一定相等。如果只重写 equals 而不重写 hashCode，相等的对象会落到不同的桶里，导致 get 取不到值、去重失效。另外不要把可变对象作为 key，对象加入 Map 后再修改参与哈希计算的字段，同样会导致查不到。

## Set 与有序 Map

HashSet 内部就是一个 HashMap，元素作为 key，value 是一个固定的占位对象，所以去重规则同样是 equals 加 hashCode。LinkedHashMap 继承 HashMap，额外用双向链表维护顺序，默认是插入顺序；构造时把 accessOrder 设为 true 就变成访问顺序，可以用来实现简单的 LRU 缓存。TreeMap 基于红黑树，按 key 排序，key 必须实现 Comparable 或者构造时传入 Comparator，查找、插入、删除都是 O(log n)。

## 遍历与并发修改

for-each 循环编译后就是 Iterator 遍历。集合内部维护 modCount，迭代过程中如果集合结构被修改（add、remove），modCount 与迭代器保存的 expectedModCount 不一致，就会抛出 ConcurrentModificationException。正确做法是使用 Iterator.remove()，或者 Java 8 的 Collection.removeIf。即使只是单线程，在 for-each 里调用 map.remove 也会触发这个异常。

Arrays.asList 返回的是固定长度的列表视图，底层仍是原数组，调用 add、remove 会抛 UnsupportedOperationException。subList 返回的是原列表的视图而不是拷贝，修改子列表会影响原列表。Collections.unmodifiableList 返回只读视图，原列表的后续修改仍然可见。

## 选择建议

绝大多数业务场景优先使用 ArrayList；需要频繁头部插入或当作栈、队列时使用 ArrayDeque；需要按 key 排序用 TreeMap，需要保留插入顺序用 LinkedHashMap；需要线程安全时用 ConcurrentHashMap、CopyOnWriteArrayList 或在外层加锁。本项目中的店铺类型、优惠券列表等查询结果都是普通 ArrayList，只有跨线程共享的数据才需要考虑并发容器。
