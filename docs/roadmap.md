# AgentFlow 12 周开发路线图

> 每周投入 15 小时以上。假设节奏被打断也没关系，按 Phase 推进，每个 Phase 内部任务是独立的。

## 协作方式（先读这段）

**每周节奏**：以 2~3 小时为一个工作块，一块只做一件事（写代码 / 跑通 / 复盘）。

**AI 协作纪律**（这条决定这个项目有没有面试价值）：
1. 每个合入的文件，你要能回答"为什么这么写，换一种方式行不行"；
2. 三个核心模块——**DAG 引擎、SSE 流式、Agent 循环**——核心代码要求合上资料能自己手写出来；
3. 每个 Phase 结束做一次"面试模拟"：对照下面的面试题口述作答，答不上来的回去补。

**每个 Phase 的出口条件**：能跑（演示录屏或命令复现）+ 能讲（面试题过关）+ 有记录（提交记录、遇到的问题写进 docs/notes）。

---

## Phase 0（本周）：环境与骨架 ✅ 起点已就绪

**任务**
- [x] 安装 JDK 21（Temurin 21.0.12.1，免安装版，位于 `~/.jdks/jdk-21.0.12.1+1`）
- [x] 编辑器就绪：VS Code + Java/Spring 扩展包（已装好，`.vscode/` 已配置 JDK 指向和启动项）
- [x] Maven 阿里云镜像（已配在 `~/.m2/settings.xml`，IDE 也会自动使用）
- [x] LLM API Key 已注册
- [ ] 配置 key 环境变量：在**新开的**终端执行 `setx LLM_API_KEY "你的key"`。注意 setx 对当前窗口不生效，重开终端才有
- [ ] 自己跑通一次：`./mvnw spring-boot:run`（或 VS Code 运行面板跑 `AgentFlow`），curl 验证 `/api/ping` 和 `/actuator/health`
- [ ] 通读 `Result.java`、`GlobalExceptionHandler.java`、`PingController.java`，理解 @RestController / 依赖注入 / 统一返回
- [ ] （第 3 周前）安装 Docker Desktop

**验收**：能画出"一次 HTTP 请求从进来到返回经过了哪些层"。

**已就绪的环境**：JDK 21（`~/.jdks/jdk-21.0.12.1+1`）、VS Code 扩展（Java Pack + Spring Boot Pack）、Maven 阿里云镜像（`~/.m2/settings.xml`）。骨架已验证可编译、可启动。

---

## Phase 1（第 1-2 周）：对话闭环 —— Spring 基础 + LLM + SSE

**目标**：不接数据库，先把"流式对话"跑通。Spring 概念全部在动手中学，不单独刷教程。

**任务**
- [x] 1.1 `llm/` 包：用 WebClient 封装 OpenAI 兼容客户端（chat/completions，支持流式），key 从配置读、不进 git（流式 streamChat 留到 1.3）
- [x] 1.2 `POST /api/chat`：阻塞式对话，请求体校验（@Valid），接 LLM 返回完整回答
- [x] 1.3 `GET /api/chat/stream`（或 POST + SseEmitter）：SSE 流式逐 token 输出
- [x] 1.4 会话上下文：内存 Map 保存最近 N 轮对话，支持多会话 id
- [x] 1.5 处理流中的异常：LLM 中途断流时向客户端发 error 事件（回看 GlobalExceptionHandler 里的注释）
- [ ] 1.6 用 curl / Apifox 演示三种调用并录屏

**验收**：`curl -N` 能看到逐字输出；断网/错 key 时前端收到明确错误而不是连接挂死。

**面试题（Phase 1 出口要能答）**
1. WebClient 和 RestTemplate 的区别？为什么新项目用 WebClient？
2. SSE 和 WebSocket 有什么区别？为什么 LLM 对话场景选 SSE？（提示：单向推送、HTTP 兼容、断线重连）
3. SseEmitter 底层是什么？如果客户端中途断开，服务端怎么感知？
4. 我们的流式接口里，客户端断开后为什么要 dispose()？（提示：Reactor 取消信号 → 停止上游 HTTP 调用 → 省 token 钱）
5. 流式接口的错误处理和普通接口有什么本质区别？（提示：HTTP 状态码只能在"流开始前"设置）
6. 本项目 HTTP 状态码恒为 200、错误码放 body——这个风格的优缺点？网关监控会受什么影响？
7. 如果同时来 1000 个长连接对话请求，Tomcat 默认 200 线程会发生什么？怎么解？（为 P4 虚拟线程埋伏笔）
8. LLM API 是无状态的，多轮对话的"记忆"是怎么实现的？
9. 会话历史越来越长怎么办？（截断 / 老消息摘要压缩 / 按相关性检索——RAG 的思想提前露头）
10. 内存版会话存储有什么缺陷？如何演进到分布式？（Phase 2 埋伏笔）

---

## Phase 2（第 3-4 周）：存储层 + RAG 管线

**目标**：引入 MySQL/Redis，完成"上传文档 → 切分 → 向量化 → 检索 → 带引用回答"全链路。

**任务**
- [x] 2.1 `docker compose up -d` 起 MySQL/Redis；开启 application.yml 中数据源配置
- [x] 2.2 Spring Data JPA 实体：conversation / message 落库，替换内存上下文（实现为单表 chat_message，按 session_id 分组；"存全部、取最近 20 条进上下文"）
- [ ] 2.3 `rag/` 包：文档上传接口，分块（按段落 + overlap），调用 embedding API 向量化（注意：**DeepSeek 没有 embedding 接口**，届时用智谱 embedding-3 或本地 bge-m3，主对话模型仍走 DeepSeek）
- [ ] 2.4 向量存储 MVP：MySQL 存 JSON 数组，检索时内存暴力余弦相似度（**故意先这样做**，P5 有升级故事）
- [ ] 2.5 混合检索：关键词过滤 + 向量 TopK，简单加权融合
- [ ] 2.6 对话接口支持 `kbId`：命中片段带进 prompt，回答标注引用来源
- [ ] 2.7 Redis：会话缓存 + 接口级限流（Fixed Window 即可，讲清缺点）

**验收**：上传一份 PDF/Markdown，提问后回答附带"引用了第几段"；重启服务会话不丢。

**面试题**
1. 为什么分块要有 overlap？块太大/太小分别有什么问题？
2. 暴力余弦检索的复杂度？数据到多少条会撑不住？有什么索引结构？（HNSW 说个概念即可）
3. 缓存穿透/击穿/雪崩的区别和对策？
4. JPA 的 N+1 问题是什么时候发生的，怎么解决？

---

## Phase 3（第 5-7 周）：自研 DAG 工作流引擎（核心卖点）

**目标**：平台化的关键。工作流 = JSON DSL，引擎负责调度执行。

**任务**
- [ ] 3.1 DSL 设计：`nodes[] + edges[]`，节点类型：llm / tool / kb / condition；写 JSON Schema 校验
- [ ] 3.2 解析与校验：拓扑排序，检测环、孤立节点、多父汇入
- [ ] 3.3 执行引擎 v1：单线程顺序执行，节点上下文传递（上游输出 → 下游输入）
- [ ] 3.4 执行引擎 v2：无依赖的节点**并发执行**（线程池 + CompletableFuture）
- [ ] 3.5 可靠性：节点超时、失败重试（指数退避）、checkpoint（每节点完成即持久化状态，失败可从断点恢复）
- [ ] 3.6 condition 节点：按上游输出路由到不同分支
- [ ] 3.7 SSE 推送节点级事件（节点开始/结束/失败），做一个极简调试页能看到节点状态流转

**验收**：一个含 5+ 节点、带分支的工作流 JSON 跑通；人为让某节点失败，重启后从 checkpoint 恢复继续执行。

**面试题**
1. 拓扑排序怎么做？Kahn 和 DFS 两种写法的区别？
2. 并发执行时怎么保证"下游节点等所有上游完成"？如果某个上游失败，下游已启动的怎么办？
3. checkpoint 存什么？恢复时怎么保证幂等（不重复执行已完成的节点）？
4. 为什么不用现成的工作流引擎（Temporal / Camunda）？——练习"自研的理由与边界"这种追问。

---

## Phase 4（第 8-9 周）：Agent 循环 + MCP 工具生态

**目标**：工作流节点里能跑真正的 Agent；工具通过 MCP 协议接入。

**任务**
- [ ] 4.1 自研 Function Calling 循环：解析模型返回的 tool_calls → 执行 → 结果回填 → 循环直到产出最终回答（不依赖 LangChain4j，自己写）
- [ ] 4.2 内置工具 2 个：网页抓取、SQL 查询（只读账号 + 白名单表，写清安全考虑）
- [ ] 4.3 MCP：用 MCP 官方 Java SDK 写 1 个 MCP Server（把 4.2 的工具暴露成 MCP），主项目实现 MCP Client 接入
- [ ] 4.4 并发治理：LLM 调用信号量 + 令牌桶限流；调用层超时与降级（超时返回兜底文案而不是挂死）
- [ ] 4.5 幂等：对话请求带 requestId，重试不重复计费/执行
- [ ] 4.6 虚拟线程：Tomcat 切换虚拟线程处理长连接，压测对比线程模型前后差异

**验收**：Agent 能自主决定"先搜索再回答"；并发 50 路对话不会被 LLM 慢响应拖垮服务。

**面试题**
1. Function Calling 的完整交互流程？模型"调用"工具时到底发生了什么？
2. MCP 解决了什么问题？和直接 Function Calling 相比架构上有什么变化？
3. 令牌桶和漏桶的区别？你为什么选令牌桶？
4. 虚拟线程为什么适合 LLM 网关这种 IO 密集场景？什么时候不适合（CPU 密集 / pinned）？

---

## Phase 5（第 10-11 周）：可观测 + 评测 + 压测（数据卖点）

**目标**：把"感觉能用"变成"有数据证明能用"。这是简历上最值钱的部分。

**任务**
- [ ] 5.1 LLM 调用 trace：每次调用的 token 用量、耗时、成本落库，做个简单统计接口
- [ ] 5.2 评测集 30~50 条问答 + 标注：算检索命中率（Hit@K）、Agent 任务成功率，跑出基线数字
- [ ] 5.3 检索优化一轮：调 chunk 大小 / TopK / 加 Rerank（API 或本地 bge-reranker），对比评测分数**用数字说话**
- [ ] 5.4 压测：JMeter 或 Gatling 压 SSE 长连接，记录 QPS / P99 / 错误率，写进 docs/benchmark.md
- [ ] 5.5 向量存储升级：Milvus（docker 单机版）替换暴力检索，对比延迟数据
- [ ] 5.6 演示前端：静态 HTML + JS 单页（对话 + 工作流调试视图），不用框架

**面试题**
1. 你的 RAG 效果怎么量化？Hit@K 怎么算？
2. 压测方案怎么设计的？瓶颈在哪，怎么定位的？
3. 从暴力检索到 Milvus，延迟从多少降到多少？为什么？

---

## Phase 6（第 12 周）：收尾

- [ ] README 终稿：架构图、演示 GIF、量化指标置顶
- [ ] 技术博客 1~2 篇（建议写：《自研 DAG 引擎的并发调度与断点恢复》《RAG 评测驱动优化实录》）
- [ ] 简历项目描述定稿（对齐 README 目标态描述 + 数字）
- [ ] 全项目"为什么"复盘：过一遍每阶段的面试题，全部口述过关
- [ ] （可选）部署到一台最低配云服务器 + 域名，或用内网穿透做在线 Demo

---

## 进度日志

| 日期 | 完成内容 | 踩坑记录 |
|---|---|---|
| 2026-09-18 | P0 骨架搭建，编译+启动验证通过 | Windows 下 Spring Initializr 已只提供 Boot 4.x；本机原 JDK 11；GitHub 直连失败，JDK 改从清华 TUNA 镜像下载 |
| 2026-09-19 | VS Code 环境配置（Java/Spring 扩展 + JDK 指向 + 启动项），API Key 就绪 | API key 走环境变量 `LLM_API_KEY`，不进 git |
| 2026-09-19 | 项目迁移到 `E:\Flow_item`（git 仓库根目录） | ZCode 文件监视器占用原目录导致整体 rename 失败，改为逐文件移动后删除空壳 |
| 2026-09-19 | P1.1+1.2 代码完成：LlmProperties / DeepSeekClient / ChatController / BizException，三条错误路径冒烟通过 | 坑1：Boot 4 模块化后 `WebClient.Builder` 的自动配置在 `spring-boot-starter-webclient`，不在 starter-webflux；坑2：Windows Git Bash 用 GBK 发中文 JSON 导致服务端 UTF-8 解析失败——测试请求含中文时注意编码。**待办：用户在自己终端用真实 key 跑通真实 LLM 调用** |
| 2026-09-19 | 设置用户级 `JAVA_HOME` 指向 `~/.jdks/jdk-21.0.12.1+1` | 坑3：终端里没设 JAVA_HOME 时，mvnw 顺着 PATH 找到老 JDK 11 去跑 Maven，报 `UnsupportedClassVersionError`（class version 55=Java11，61=Java17，65=Java21，看版本号就能判断谁编译的、谁在跑） |
| 2026-09-19 | ✅ **P1.1+1.2 用户验收通过**：真实 key 调通 DeepSeek | 全链路：终端 → Spring MVC → WebClient → DeepSeek → 统一返回 |
| 2026-09-19 | P1.3+1.5 代码完成：streamChat（SSE 解析 → Flux\<String\>）+ `/api/chat/stream`（SseEmitter）+ 流内 error 事件 + 断连 dispose；新增 404 处理。8081 冒烟通过 | 坑4：本地测试请求打到了**别人还开着的旧实例**上（8080 被用户验收用的进程占用），新实例起不来——**测试前先确认"我在测哪个实例"**，并行调试用不同端口；坑5（设计取舍）：本项目 HTTP 状态码恒 200、错误码放 body，监控按 HTTP 统计错误率会失真，Phase 5 可观测时回看 |
| 2026-09-19 | 排障：流式接口读不到 key | 坑6：`setx` 只对之后新开的进程生效，旧终端里重启应用照样读不到 `LLM_API_KEY`——**setx 之后必须开新窗口再启动应用**；附：PowerShell 的 curl 是假 alias（用 curl.exe）、5.1 传参会吞内嵌引号（body 走 `--data-binary @文件` 最稳） |
| 2026-09-19 | ✅ **P1.3+1.5 用户验收通过**：SSE 打字机效果跑通（VS Code 调试器 F5 启动的实例） | 坑7：WebClient 消费 SSE 必须用 `ServerSentEvent<String>` 泛型，`bodyToFlux(String.class)` 会静默空流；坑8：错误消费者必须同时记日志，否则服务端零痕迹；坑9：终端 mvnw、F5 调试器、孤儿进程会抢同一个端口，启动前 `netstat -ano | findstr :8080` 先看谁占着 |
| 2026-09-19 | ✅ **P1.4 完成并验证**：LlmMessage 抽取 / ConversationStore（内存，上限 20 条截断）/ 两接口接入多轮上下文 / session 事件先行。真实 LLM 验证：同会话记住"小明+7"，新会话明确失忆 | 坑10（设计记录）：流式接口的完整回答只在流成功结束后写入历史，半截回答不污染上下文；sessionId 由客户端生成传递，服务器只认 id 不认人——无状态服务的雏形 |
| 2026-09-20 | ✅ **P1.4 用户验收通过**；演示页提前到 P1 做了最小版：`static/index.html`（fetch 手动读 SSE 流 + localStorage 存 sessionId），P5.6 届时只打磨 | 坑11：VS Code 把 .http 文件里的 URL 渲染成超链接，点 URL = 浏览器打开 = GET 请求——要点的是请求上方灰色的 "Send Request"（CodeLens），成功标志是右侧弹出响应面板；配套加了 `RequestLogFilter` 访问日志排障，"让服务器说出它收到了什么" |
| 2026-09-20 | Docker Desktop（per-user 版，WSL2 backend）装好；配置镜像加速器后拉取恢复正常；`docker compose up -d` 拉起 MySQL+Redis，agentflow 库连通 | 坑12：Docker Hub 国内直连超时——实测镜像站存活：docker.m.daocloud.io / docker.1ms.run / docker.xuanyuan.me 可用（/v2/ 返回 401 是健康标志），dockerproxy.net 已死；配置在 Docker Desktop → Settings → Docker Engine 的 `registry-mirrors` |
| 2026-09-20 | ✅ **P2.2 完成并验证**：ChatMessageEntity + ChatMessageRepository + ConversationStore 换数据库实现（调用方零改动）；重启应用后同 sessionId 记忆仍在（4 行数据横跨两次进程生命周期） | 坑13：YAML 里注释不隔断结构，`datasource:` 写在 management 段后面、因缩进被解析成它的子项，Spring 读不到数据源——**YAML 配置块必须确认挂在正确的顶层键下**；DTO 用 record / 实体用普通类（JPA 需无参构造+可变） |
| 2026-09-20 | 密钥管理切换：环境变量 LLM_API_KEY 已删除，两平台 key 统一放 `application-local.yml`（gitignored，spring.config.import 导入；`.env` 是 Node 约定，Spring 原生做法是本地配置文件；文件里的属性名故意与环境变量同名，作为 `${LLM_API_KEY:}` 占位符的取值来源，解析顺序=系统环境变量 > 本地文件 > 空默认） | 占位符方案要实测"无环境变量"场景——本机残留变量差点掩盖了配置不生效的风险；application-local.yml 若误提交需立即作废重发 key |
| 2026-09-22 | 聊天页升级为控制台：会话侧栏（新增 GET /api/sessions 与 /{id}/messages，JPQL 接口投影做分组统计）、会话切换/历史回放/当前高亮、刷新自动回到上次会话；浏览器截图自审抓出并修复：流式回答单词粘连（见坑16） | 坑16：SSE 文本负载不能发"裸字符串"——规范要求客户端吃掉 `data:` 后的一个空格，DeepSeek 增量自带前导空格（" How"）恰好被吞。**服务端 JSON 编码增量 + 客户端 JSON.parse**，顺带免疫增量内换行破坏帧；前端任务必须截图自审（browser-use），DOM 快照看不出视觉问题 |
| 2026-09-22 | 修复长对话无法滚动：滚动链路（body→main→#msgs）各容器补 `min-height:0`；顺带修了启动续接被 openSession 的 `id===currentId` 守卫拦截、历史不加载的 bug（改为 `loadedId` 已加载标记）。矮视口实测：8 条气泡可滚、自动停在最新、页面不溢出。另：短对话消息锚定底部（`#msgs > :first-child { margin-top:auto }`，比 justify-content:flex-end 安全）；再修 grid 隐式行按内容收缩导致短对话时整个应用缩在窗口顶部（body 显式 `grid-template-rows: minmax(0,1fr)`，2000×1015 视口复验满铺） | 坑17：grid/flex 子项默认 `min-height:auto` 会拒绝收缩，内容撑破外层后内部 `overflow-y:auto` 永不触发——**滚动链上每个容器都要 min-height:0**；坑18：守卫条件用"目标==当前"会误伤"启动时自动加载当前会话"，防重复加载应判断"已加载标记"；坑19：**grid 容器不定义 grid-template-rows 时隐式行按内容收缩**，"高度 100vh 的 grid"不会自动把行撑满——布局异常先查"行/列轨道有没有显式定义" |
| 2026-09-20 | MySQL 宿主机端口迁移 3306→13306；容器加 `restart: unless-stopped`（Docker 启动自拉起） | 坑14：Windows(Hyper-V/WSL) 重启后会随机保留端口段（netsh interface ipv4 show excludedportrange protocol=tcp 查看），3306 被圈进 3252-3351 导致绑定失败——用非常规端口绕开；坑15：机器睡眠后 WSL 时钟漂移，容器 Exited 时间戳不可信，看状态别看时间 |
| 2026-11-01 | 热身完成：上游 401/402/403/404/429/5xx 统一翻译成带指引的中文提示（DeepSeekClient.onErrorMap → BizException），阻塞路径与 SSE 错误事件实测均友好报错、不再落兜底 500 | 坑20：WebClient 对非 2xx 抛 WebClientResponseException，不拦就落进兜底处理器变笼统 500——在客户端层 onErrorMap 翻译，阻塞/SSE 两条路径同时受益；坑2 复现确认：Git Bash curl 直接发中文 body 是 GBK 编码，测试一律 `--data-binary @UTF-8文件`；坑21（外部）：智谱 key 失效（国内外两个端点 1000 身份验证失败，9 月底还实测通过）——key 会在无声无息中过期，上线前要加"启动时探活"类检查 |
