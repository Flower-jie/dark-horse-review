# Spring MVC 请求链路、事务与 MyBatis-Plus

## 一次请求的完整链路

浏览器发起请求后，先经过 Servlet 容器中的 Filter 链，例如字符编码过滤器和鉴权过滤器；然后进入 Spring MVC 的前端控制器 DispatcherServlet。DispatcherServlet 通过 HandlerMapping 找到能处理该 URL 的 handler，同时组装出拦截器链；接着由 HandlerAdapter（最常用的是 RequestMappingHandlerAdapter）调用对应的 Controller 方法。

调用之前要先解析参数：@RequestParam 取查询参数，@PathVariable 取路径变量，@RequestBody 借助 HttpMessageConverter 把 JSON 反序列化成对象。方法返回后，@ResponseBody 或 @RestController 会再次借助 HttpMessageConverter 把对象序列化成 JSON 写回响应。过程中出现异常时，由 HandlerExceptionResolver 处理，@RestControllerAdvice 配合 @ExceptionHandler 是统一异常出口。本项目的 WebExceptionAdvice 就捕获 RuntimeException 并返回统一的 Result。

Filter 属于 Servlet 规范，Interceptor 属于 Spring MVC，可以访问 handler 并在 preHandle 返回 false 时中断请求；AOP 作用在方法调用层，三者执行顺序大致是 Filter、Interceptor、AOP、Controller。

## 拦截器的注册与排除

本项目的 MvcConfig 注册了两个拦截器：RefreshTokenInterceptor 匹配 /**，order 为 0，负责从 Redis 刷新 token 并写入 ThreadLocal；LoginInterceptor 的 order 为 1，检查 ThreadLocal 中是否有用户。excludePathPatterns 只对同一拦截器 addPathPatterns 匹配到的路径生效，所以把登录、店铺、上传等公开接口排除在 LoginInterceptor 之外，它们依然会经过 RefreshTokenInterceptor。AI 接口使用独立令牌，不依赖业务登录，因此也在排除列表中。

## 参数校验与统一响应

参数校验可以直接写代码判断，也可以使用 JSR-303 注解配合 @Valid，校验失败会抛出 MethodArgumentNotValidException，交给全局异常处理器转换成统一的错误响应。统一的响应体通常包含 success、errorMsg、data 三个字段，前端据此判断结果。

## 事务

@Transactional 基于 Spring AOP 代理实现。类内部方法直接调用另一个带事务的方法时不会经过代理，事务不生效，这是最常见的失效原因；解决办法是把方法拆到另一个 Bean，或者注入自身代理。它默认只对 RuntimeException 和 Error 回滚，受检异常需要显式配置 rollbackFor；只对 public 方法生效；同一个类中方法的事务属性不会被继承。

传播行为默认是 REQUIRED，表示加入当前事务，没有事务就新建一个；REQUIRES_NEW 会挂起当前事务并新开一个独立事务，适合记录日志这类不希望被回滚的操作。事务是否生效还取决于存储引擎，MySQL 的 InnoDB 支持事务，MyISAM 不支持。事务中应尽量避免远程调用和长时间操作，否则会长时间占用数据库连接和行锁。

## MyBatis-Plus 的基本用法

MyBatis-Plus 在 MyBatis 之上提供了通用 CRUD。Mapper 接口继承 BaseMapper<T> 就能直接使用 insert、deleteById、updateById、selectById、selectList 等方法；Service 层继承 IService 和 ServiceImpl 可以获得 save、removeById、list、page 等方法。

实体类上用 @TableName 指定表名，@TableId 指定主键及生成策略，例如 IdType.AUTO 表示数据库自增，@TableField 用于映射普通字段或标记不存在的字段。条件构造器 QueryWrapper 和 LambdaQueryWrapper 可以链式拼装 eq、like、in、gt、orderByDesc 等条件，LambdaQueryWrapper 用方法引用取字段名，能避免手写字符串出错。

分页需要注册 MybatisPlusInterceptor 并添加 PaginationInnerInterceptor，本项目在 MybatisConfig 中注册了 MySQL 方言的分页插件；不注册插件时直接调用 page 不会真正分页。复杂的多表 SQL 写在 XML 中，XML 的 namespace 必须等于 Mapper 接口的全限定名，方法 id 与接口方法名一致。

## SQL 与缓存细节

MyBatis 中 #{} 是预编译占位符，会生成 PreparedStatement 参数，能防止 SQL 注入；${} 是字符串直接拼接，只应在表名、排序字段等完全可控的场景使用。MyBatis 有一级缓存（同一个 SqlSession 内生效）和二级缓存（跨 SqlSession，按 namespace 隔离，需要显式开启），在数据频繁变化的业务里通常保持默认关闭更安全。

配置上，type-aliases-package 指定实体别名包，mapper-locations 指定 XML 的位置；@SpringBootApplication 等价于 @Configuration、@EnableAutoConfiguration 和 @ComponentScan 的组合。
