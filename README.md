## Leaf
解析美团的分布式id框架Leaf-MySQL版本

## Introduction
### 数据库自增ID存在固有的弊端：
* 导入旧数据，可能会ID重复，导致导入失败
* 分布式架构中，多个MySQL实例可能会ID重复   

### Leaf秉承的四个特性：
1.全局唯一，绝对不会出现重复的ID，且ID整体趋势递增。  
2.高可用，服务完全基于分布式架构，即使MySQL宕机，也能容忍一段时间的数据库不可用。  
3.高并发低延时，在CentOS 4C8G的虚拟机上，远程调用QPS可达5W+，TP99在1ms内。  
4.接入简单，直接通过公司RPC服务或者HTTP调用即可接入。 

### 服务处理流程
![leaf的处理流程](pics/leaf处理流程.png)
* Leaf Server 1：从DB加载号段[1，1000]
* Leaf Server 2：从DB加载号段[1001，2000]
* Leaf Server 3：从DB加载号段[2001，3000]  


用户通过Round-robin的方式调用Leaf Server的各个服务，所以某一个Client获取到的ID序列可能是：1，1001，2001，2，1002，2002……  
也可能是：1，2，1001，2001，2002，2003，3，4……当某个Leaf Server号段用完之后，下一次请求就会从DB中加载新的号段，这样保证了每次加载的号段是递增的。  

Leaf数据库中的号段表格式如下：
```sql
CREATE DATABASE leaf
CREATE TABLE `leaf_alloc` (
  `biz_tag` varchar(128)  NOT NULL DEFAULT '',
  `max_id` bigint(20) NOT NULL DEFAULT '1',
  `step` int(11) NOT NULL,
  `description` varchar(256)  DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`biz_tag`)
) ENGINE=InnoDB;
```

Leaf Server加载号段的SQL语句如下：

```sql
Begin
UPDATE table SET max_id=max_id+step WHERE biz_tag=xxx
SELECT tag, max_id, step FROM table WHERE biz_tag=xxx
Commit
```
最巧妙的点，用了数据库事务的原子性保证了线程安全。

### 双Buffer
如果发生数据库宕机或者发生主从切换，会导致一段时间的获取id的服务不能使用，为此美团进行了优化，采用异步更新的策略，通过双Buffer的机制，无论何时数据库出现问题，
都能有一个Buffer的号段可以正常对外提供服务，只要数据库在一个Buffer的下发周期内恢复，就不会影响整个Leaf的可用性。

![leaf的处理流程](pics/双Buffer.png)

后续代码分析中会做进一步的讨论。

### Leaf动态调整Step
假设服务QPS为Q，号段长度为L，号段更新周期为T，那么Q * T = L。最开始L长度是固定的，导致随着Q的增长，T会越来越小。但是Leaf本质的需求是希望T是固定的。
那么如果L可以和Q正相关的话，T就可以趋近一个定值了。所以Leaf每次更新号段的时候，根据上一次更新号段的周期T和号段长度step，来决定下一次的号段长度nextStep：
* T < 15min，nextStep = step * 2
* 15min < T < 30min，nextStep = step
* T > 30min，nextStep = step / 2

后续代码中会做进一步的论证。

 
## Code
### leaf-core子项目
segment包中，model包下面有三个实体类，分别是[LeafAlloc](./leaf-core/src/main/java/com/sankuai/inf/leaf/segment/model/LeafAlloc.java)，[Segment](./leaf-core/src/main/java/com/sankuai/inf/leaf/segment/model/Segment.java)，[SegmentBuffer](./leaf-core/src/main/java/com/sankuai/inf/leaf/segment/model/SegmentBuffer.java)。请自行查看，不在赘述。 <br>
dao包中，[IDAllocDao](./leaf-core/src/main/java/com/sankuai/inf/leaf/segment/dao/IDAllocDao.java)定义了数据库的相关操作。
```java
public interface IDAllocDao {
    
    /**
     * 获取所有的LeafAlloc
     *
     * @return List<LeafAlloc>
     */
    List<LeafAlloc> getAllLeafAllocs();

    /**
     * 使用事务保证这两步的原子性(事务的隔离机制)
     * 根据数据库中对应tag的step来更新max_value,同时获取 tag的信息
     * 1. UPDATE leaf_alloc SET max_id = max_id + step WHERE biz_tag = #{tag}
     * 2. SELECT biz_tag, max_id, step FROM leaf_alloc WHERE biz_tag = #{tag}
     *
     * @param tag
     * @return
     */
    LeafAlloc updateMaxIdAndGetLeafAlloc(String tag);

    /**
     * 动态调整MaxId step周期性发生变化
     *
     * @param leafAlloc
     * @return LeafAlloc
     */
    LeafAlloc updateMaxIdByCustomStepAndGetLeafAlloc(LeafAlloc leafAlloc);

    /**
     * 获取所有的tag
     *
     * @return List<String>
     */
    List<String> getAllTags();
}
```
leaf包下面，有[IDGen](./leaf-core/src/main/java/com/sankuai/inf/leaf/IDGen.java)(ID生成器)，定义了两个方法。
```java
public interface IDGen {

    /**
     * 获取返回的结果集
     *
     * @param key
     * @return Result
     */
    Result get(String key);

    /**
     * 初始化方法
     *
     * @return boolean
     */
    boolean init();
}
```
基于MySQL的ID生成器的核心代码就是[SegmentIDGenImpl](./leaf-core/src/main/java/com/sankuai/inf/leaf/segment/SegmentIDGenImpl.java)，代码里面有明确的注释。<br>
解析详情请见[SegmentIDGenImpl解析](./docs/SegmentIDGenImpl解析.md)。

### leaf-server子项目
service包中，SegmentService负责提供ID，主要是getId(String key)方法
```
public Result getId(String key) {
    return idGen.get(key);
}
```