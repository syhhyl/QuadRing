# QuadRing 的 CHI 子集：事务与 Ring 传输

本文假设读者熟悉 CHI 的基本概念（REQ/RSP/SNP/DAT 通道、`ReadShared`/`ReadUnique`、snoop、I/SC/UC/UD、`CompAck`、DBID 等）。这里不再解释协议本身，而是回答：

1. 这个实现支持 CHI 的哪个子集，和标准有哪些偏差？
2. 每笔事务在四个端点上分别怎么实现，消息在四条物理环上怎么走？
3. 完成语义、重试和进展假设是什么？
4. 哪些结构是常规硬件写法，哪些是范围内刻意的串行化？

所有结论以 `src/main/scala` 为准。图中的箭头表示一条消息在某个通道的环上传输了一趟，不表示连线或单周期行为。

本文只描述 `coherent = true` 的配置。`ReadNoSnp` 和 `WriteNoSnpFull` 仅作为 HN 访问 SN 的内部请求出现，不作为 RN 的事务形态讨论。

---

## 1. 实现范围与约定

### 1.1 端点和拓扑

固定为两个 RN、一个 HN、一个 SN。物理环顺序 `RN0 → HN → RN1 → SN → RN0`，四个通道各有一条独立单向环。默认节点 ID 等于物理位置（0/1/2/3），但 `ChiNoC` 会将 `tgtID` 映射到物理 `dst`，因此支持稀疏或打乱的 ID；重复 ID 在 elaboration 阶段失败，未知 ID 在发送时断言。

每个端点同时只处理一笔事务：RN 一个本地命令，HN 一个活动上下文，SN 一次内存操作。并发不在端点内部展开，而是靠 HN 串行化加协议重试排队。

### 1.2 数据粒度和 DAT

- 行大小 64 字节，所有地址必须 64 字节对齐（低 6 位为零）。
- 一次 DAT 传输承载完整 512 bit 行，没有多 beat、没有字节掩码。
- 所有写都是整行替换；没有部分写、没有 `WriteUnique` 系列。

### 1.3 内部 flit 与标准 CHI 线格式的差异

内部 flit 是精简结构，不是完整的 CHI 线格式：

| 通道 | 字段 | 默认位宽 |
|---|---|---:|
| REQ | `tgtID, srcID, txnID, opcode, addr, allowRetry, pCrdType` | 67 |
| RSP | `tgtID, srcID, txnID, opcode, resp, dbID, pCrdType` | 23 |
| SNP | `srcID, txnID, opcode, addr` | 59 |
| DAT | `tgtID, srcID, txnID, opcode, resp, dbID, data` | 530 |

没有 `MemAttr`、`Size`、`NS`、`Order`、`QoS`、`PBHA`、`Expect`/`ReturnNID`、`FwdNID`、`VMID` 等标准字段。REQ 和 SNP 的 `addr` 是包含 6 bit 行内偏移的完整字节地址，默认 48 bit。SNP 没有 `tgtID`，由发送方额外提供 `dst` 供环路由。

`opcode` 宽度：REQ 6 bit、RSP 4 bit、SNP 5 bit、DAT 3 bit。`pCrdType` 4 bit，本实现只用 type 0。cache 状态 `I/SC/UC/UD` 用 2 bit 内部编码；线协议 `resp` 用 3 bit，包含 `I_PD/SC_PD/UD_PD`，两套编码不可混用。

### 1.4 ID 语义

- 上游请求身份是 `(srcID, txnID)`；两个 RN 可以复用相同 `txnID`，HN 以 `srcID` 区分。
- HN 在本地保存上游 `(srcID, txnID)` 作为事务身份并用于重试校验；向下游发请求时 `srcID` 改为 HN 自己，`txnID`、snoop `txnID`、HN/SN 的 `dbID` 都恒为 0——因为同一时刻只有一笔下游或 snoop 事务。
- `CompData`、`Comp`、`CompDBIDResp`、`DBIDResp` 使用对应请求的 `txnID`。
- `CompAck` 和写 DAT 把收到的 `dbID` 填入 `txnID` 字段来标识数据槽。这是本实现的约定，不是完整 CHI 的通用规则。

### 1.5 cache 和目录

系统运行在 `ChiSystemParams(coherent = true)` 配置：

- RN 有直接映射 cache，默认 8 行，索引取 `addr` 的中间位，标签位宽由地址宽度推出。
- 读命中 I 以外的任意有效状态都本地完成；写命中 UC/UD 本地完成并转 UD；写命中 SC 走 `ReadUnique` 升级。
- HN 目录默认 64 行，每行两个 holder 槽位，**不淘汰**。目录必须覆盖整个 SN 内存，否则 elaboration 失败。
- 目录中 UC 记录同时覆盖 RN 侧的 UC 和 UD：RN 的本地写可以在不通知 HN 的情况下把 UC 变成 UD。
- RN 不直接发 `ReadNoSnp`/`WriteNoSnpFull`；它们只作为 HN 访问 SN 的下游请求（见 4.10）。

### 1.6 复位

复位清空所有环 FIFO、cache、目录、重试表和等待表，并把 SN 内存恢复为配置的初始内容。脏数据不跨复位保留。

---

## 2. Ring 传输层

### 2.1 拓扑与跳数

四个 stop 在四条环上顺序相同：

```text
                环方向（顺时针）
        RN0  ────────────────────────►  HN
         ▲                               │
         │                               │
         │                               ▼
        SN   ◄────────────────────────  RN1

        物理位置索引：0=RN0, 1=HN, 2=RN1, 3=SN
```

单向绕行导致往返跳数不对称：

| 从 \ 到 | RN0 | HN | RN1 | SN |
|---|---:|---:|---:|---:|
| **RN0** | – | 1 | 2 | 3 |
| **HN** | 3 | – | 1 | 2 |
| **RN1** | 2 | 3 | – | 1 |
| **SN** | 1 | 2 | 3 | – |

例如 RN0 到 SN 要 3 跳，SN 回 RN0 只要 1 跳。后文的路径表都基于这个顺序。

### 2.2 RingStop 的仲裁

每个 stop 的结构：

```text
                    ┌──────────────────────────┐
 ringIn ──────────► │ FIFO（默认 3 项）         │──► head
                    └──────────────────────────┘      │
                                          ┌───────────┴───────────┐
                                          │                       │
                                    dst = 本 stop           dst ≠ 本 stop
                                          │                       │
                                          ▼                       ▼
                                    local.eject          ┌────────────────┐
                                                         │ 输出仲裁器      │──► ringOut
 local.inject ─────────────────────────────────────────► │ 转发优先        │
                                                         └────────────────┘
```

实现上的四个要点：

1. 只有 `ringIn` 进入 FIFO；本地注入在输出仲裁器等待，不占本地 FIFO。
2. 只要 FIFO 头不是本地目的，转发就优先于注入。
3. FIFO 头若是本地目的但端点未 ready，整个 FIFO 被队头阻塞，后面的 flit 不能旁路。
4. `RingStop` 的输出是普通 `Decoupled`，不是 `Irrevocable`：在 `valid` 已拉高但未被接受期间，新到的转发可以替换尚未传输的注入。只有 `valid && ready` 才真正传输。

第 4 点容易和端点内部的行为混淆：RN 的 RSP/DAT 输出仲裁在停滞时会锁定已呈现的选择（见 4.10），那是端点自己的约束，不是 `RingStop` 的。

### 2.3 Credit 和 bubble

每个发送方维护下游 FIFO 的空位数：

```text
forward 需要 downstreamCredits >= 1
inject  需要 downstreamCredits >= 2
```

发送消耗一个 credit；下游 FIFO 出队时归还一个；同一周期发送与归还可相互抵消。注入要求至少两个空位，是为了在下游留下一个 bubble，避免单向环上的 flit 集合把环完全填满、失去腾挪空间。转发不新增 flit，因此只需一个空位。

这与 P-Credit 是两套机制：链路 credit 管 FIFO 空间，P-Credit 管端点事务容量。不要用链路 credit 推断接收方能否处理事务。

### 2.4 路由

`ChiNoC` 把 CHI 的 `tgtID` 映射成外层 `RingFlit.dst`：

- REQ/RSP/DAT 使用各自的 `tgtID`。
- SNP 使用 `RoutedSnp.dst`，因为 `SnpFlit` 没有 `tgtID`。
- 未知目标在 `valid` 时直接断言；重复端点 ID 在 elaboration 阶段失败。

传输层不解析 opcode，只按 `dst` 转发或 eject。

### 2.5 通道之间没有全局顺序

四条环物理独立。一笔事务的 REQ、RSP、SNP、DAT 可以同时在途，到达顺序没有保证。协议关联完全依赖 `txnID`、`resp`、`dbID` 等字段。一个直接后果是重试的 `RetryAck` 与 `PCrdGrant` 可以任意顺序到达，请求方必须分别记账。

---

## 3. 事务总览

### 3.1 本地命令到线上事务

| 本地命令 | 条件 | 线上事务 |
|---|---|---|
| 读 | cache 命中 SC/UC/UD | 无 |
| 写 | cache 命中 UC/UD | 无 |
| 写 | cache 命中 SC | `ReadUnique` |
| 读 | cache miss | `ReadShared` |
| 写 | cache miss | `ReadUnique` |
| 任意 | 同索引有 victim | 先 `Evict` 或 `WriteBackFull`，再发原命令 |

一个本地命令可能产生**两笔独立请求**（victim 处理 + 原命令），各自有独立的 `txnID`、独立的 `RetryAck`/`PCrdGrant` 状态。`RnNode` 用 `requestOpcode`、`victim*` 寄存器和 `resumeRequest()` 表达这一点。

### 3.2 使用的 opcode

这里先给出全集，便于查表；第 4 章会按 RN0/RN1 的真实请求说明每个 opcode 在什么场景下出现。

| 通道 | opcode | 方向 | 用途 |
|---|---|---|---|
| REQ | `ReadNoSnp` | HN→SN | HN 读内存 |
| REQ | `WriteNoSnpFull` | HN→SN | HN 写内存 |
| REQ | `ReadShared` | RN→HN | 一致读 miss |
| REQ | `ReadUnique` | RN→HN | 一致写 miss 或 SC 升级 |
| REQ | `Evict` | RN→HN | 干净替换 |
| REQ | `WriteBackFull` | RN→HN | 脏替换 |
| SNP | `SnpShared` | HN→RN | 共享 snoop |
| SNP | `SnpUnique` | HN→RN | 独占 snoop |
| RSP | `SnpResp` | RN→HN | snoop 响应（无数据） |
| RSP | `CompDBIDResp` | HN→RN | 上游写授权 |
| RSP | `DBIDResp` | SN→HN | 下游写授权 |
| RSP | `Comp` | SN→HN；HN→RN | 写完成 / Evict 完成 |
| RSP | `CompAck` | RN→HN | 一致事务确认 |
| RSP | `RetryAck` / `PCrdGrant` | 接收方→发送方 | 协议重试 |
| DAT | `NonCopyBackWrData` | HN→SN | HN 写内存数据 |
| DAT | `CopyBackWrData` | RN→HN | 脏 victim 写回 |
| DAT | `CompData` | SN→HN；HN→RN | 读数据 |
| DAT | `SnpRespData` | RN→HN | snoop 脏数据 |

HN 接受全部 6 个 REQ；RN 只会发出 `ReadShared`/`ReadUnique`/`Evict`/`WriteBackFull`，`ReadNoSnp`/`WriteNoSnpFull` 由 HN 发给 SN。SN 只接受这两个。

---

## 4. 从 RN0/RN1 的真实请求看一致性

下面每个场景都从 CPU 在某个 RN 上的本地命令开始。`ReadShared`、`ReadUnique`、`Evict`、`WriteBackFull` 不是讨论对象，而是 RN 为完成这条命令发出的消息；HN 再用 `ReadNoSnp`/`WriteNoSnpFull` 访问 SN。

这些场景共同维护两条不变量：

1. 任一行最多只有一个 RN 持有独占/写权限（UC 或 UD），其余持有者只能是 SC 或 I。
2. 只要还存在 SC 持有者，内存就必须是最新的；唯一的 UD 持有者可以把脏数据留在 cache。

HN 的目录、snoop 和 `CompAck` 完成边界，都是为在请求路径上维持这两条不变量。

### 4.1 RN 收到本地命令时的决策

| 本地命令 | cache 状态 | RN 做什么 | 线上事务 |
|---|---|---|---|
| 读 | 命中 SC/UC/UD | 直接返回 cache 数据 | 无 |
| 写 | 命中 UC/UD | 本地写，转 UD | 无 |
| 写 | 命中 SC | 需要独占权 | `ReadUnique` |
| 读 | miss | 需要数据 | `ReadShared` |
| 写 | miss | 需要数据和独占权 | `ReadUnique` |
| 任意 | miss，同索引有 victim | 先腾位置 | `Evict` 或 `WriteBackFull`，再发原命令 |

命中路径不产生任何 flit。下面的场景都是 miss、升级或替换。

### 4.2 场景 1：RN0 读 miss，没有其他副本

CPU 在 RN0 上读 A；RN0 未缓存 A，HN 目录也没有 A。

```text
RN0                     HN                      SN
 |-- REQ ReadShared ---->|                       |
 |                       |-- REQ ReadNoSnp ----->|
 |                       |<---- DAT CompData(I) -|
 |<---- DAT CompData(UC)-|                       |
 |-- RSP CompAck -------->|                       |
```

1. RN0 发 `ReadShared`（`tgtID = HN`，`srcID = RN0`）。
2. HN 查目录，没有 holder，也没有其他 RN 需要 snoop。它把 RN0 记为 UC holder，然后向 SN 发 `ReadNoSnp`。
3. SN 返回 `CompData(I)`。
4. HN 把数据以 `CompData(UC)` 发给 RN0。没有其他副本，所以直接给独占权限。
5. RN0 安装 UC，向 CPU 返回数据，并发送 `CompAck`。
6. HN 收到 `CompAck` 后才释放上下文。

完成点：CPU 的读结果在 `CompAck` 注入后才返回；HN 在收到 `CompAck` 前一直保留这笔事务和目录状态。

### 4.3 场景 2：RN1 读 miss，RN0 持有干净副本

CPU 在 RN1 上读 A；RN0 已经以 SC 或 UC 持有 A。

```text
RN1                    HN                     RN0                SN
 |-- REQ ReadShared --->|                      |                  |
 |                      |-- SNP SnpShared ---->|                  |
 |                      |<-- RSP SnpResp(SC) --|                  |
 |                      |-- REQ ReadNoSnp ----------------------->|
 |                      |<-- DAT CompData(I) ---------------------|
 |<---- DAT CompData(SC)-|                     |                  |
 |-- RSP CompAck ------->|                      |                  |
```

1. RN1 发 `ReadShared`。
2. HN 目录显示 RN0 是 holder 且不是请求者，于是发 `SnpShared`。
3. RN0 是干净的，回 `SnpResp(SC)`，自己保持 SC（如果原来是 UC，会降为 SC）。
4. snoop 响应不带数据，HN 需要自己去 SN 取数据：发 `ReadNoSnp`，SN 回 `CompData(I)`。
5. HN 把数据以 `CompData(SC)` 发给 RN1；RN1 安装 SC。
6. RN1 回 `CompAck`；HN 在目录中把 RN1 记为 SC holder。

完成点同上。

### 4.4 场景 3：RN1 读 miss，RN0 持有脏副本

CPU 在 RN1 上读 A；RN0 以 UD 持有 A，数据比内存新。

```text
RN1                    HN                     RN0                SN
 |-- REQ ReadShared --->|                      |                  |
 |                      |-- SNP SnpShared ---->|                  |
 |                      |<-- DAT SnpRespData --|                  |
 |                      |     (SC_PD, 脏数据)   |                  |
 |                      |-- REQ WriteNoSnpFull ---------------->|
 |                      |<-- RSP DBIDResp ----------------------|
 |                      |-- DAT NonCopyBackWrData -------------->|
 |                      |<-- RSP Comp --------------------------|
 |<---- DAT CompData(SC)-|                     |                  |
 |-- RSP CompAck ------->|                      |                  |
```

1. RN1 发 `ReadShared`；HN 向 RN0 发 `SnpShared`。
2. RN0 是 UD，回 `SnpRespData(SC_PD)` 并携带数据，自己降为 SC。
3. 因为 RN0 还保留共享副本，内存必须变干净。HN 先把这份脏数据写回 SN（`WriteNoSnpFull` → `DBIDResp` → `NonCopyBackWrData` → `Comp`）。
4. 写回完成后，HN 才把同一份数据以 `CompData(SC)` 发给 RN1。
5. RN1 安装 SC 并回 `CompAck`。

关键点：`SnpShared` 之后 holder 仍存在，所以先清内存；如果 holder 失效，则不需要写回，权限可以直接给 UC。

### 4.5 场景 4：RN1 写 miss，RN0 持有脏副本

CPU 在 RN1 上写 A；RN1 未缓存 A，RN0 以 UD 持有 A。

```text
RN1                    HN                     RN0
 |-- REQ ReadUnique --->|                      |
 |                      |-- SNP SnpUnique ---->|
 |                      |<-- DAT SnpRespData ---|   （I_PD）
 |<---- DAT CompData(UD_PD) --------------------|   （脏数据直接转交）
 |  安装本地写数据为 UD   |                      |
 |-- RSP CompAck ------->|                      |
```

1. 写 miss 需要独占权，RN1 发 `ReadUnique`。
2. HN 向 RN0 发 `SnpUnique`，要求失效。
3. RN0 回 `SnpRespData(I_PD)`，交出脏数据并变为 I。
4. HN **不写 SN**，把数据作为 `CompData(UD_PD)` 直接转交给 RN1。
5. RN1 不使用返回的数据，而是安装自己的写数据并置 UD，然后回 `CompAck`。

关键点：这是所有权转移，不是写内存。内存仍然是旧值，新的脏副本在 RN1。若 RN0 响应是干净的 `SnpResp(I)`，HN 仍从 SN 取数据，返回 `UC`。

### 4.6 场景 5：RN0 写命中 SC（升级）

CPU 在 RN0 上写 A；RN0 已经以 SC 持有 A。

```text
RN0                     HN                      SN
 |-- REQ ReadUnique ---->|                       |
 |                       |-- REQ ReadNoSnp ----->|
 |                       |<---- DAT CompData(I) -|
 |<---- DAT CompData(UC)-|                       |
 |  安装本地写数据为 UD   |                       |
 |-- RSP CompAck -------->|                       |
```

1. SC 不能直接写，RN0 发 `ReadUnique`。
2. HN 目录里只有请求者自己，不需要 snoop；但它仍会向 SN 重读一次（保守实现）。
3. HN 返回 `CompData(UC)`。RN0 丢弃数据，安装自己的写数据并置 UD。
4. RN0 回 `CompAck`。

关键点：这条路径没有 snoop，也没有写内存；它只是把 SC 升级成 UD。HN 重读 SN 在语义上不是必需的，但实现选择了统一路径。

### 4.7 场景 6：命中的本地请求

- 读命中 SC/UC/UD：RN 直接返回 cache 行。
- 写命中 UC/UD：RN 直接修改 cache，状态转 UD。

以上都不产生任何 flit，也不需要 snoop 或 `CompAck`。这是一致性协议的快路径；只有 miss、SC 写升级和替换才会进入线上事务。

### 4.8 场景 7：miss 且同索引有 victim

直接映射 cache 的索引被别的行占用时，RN 必须先处理 victim，再发原命令。victim 和原命令是两笔独立请求。

干净 victim（SC/UC）：

```text
RN0                    HN
 |-- REQ Evict --------->|
 |<-- RSP Comp ----------|
 |-- REQ <原命令> ------->|
```

- RN 在接受本地命令时就把 victim 移出 cache。
- `Evict` 让 HN 删除该 holder 记录并回 `Comp`；不访问 SN。
- RN 收到 `Comp` 后发原命令（`ReadShared` 或 `ReadUnique`）。

脏 victim（UD）：

```text
RN0                    HN                      SN
 |-- REQ WriteBackFull ->|                       |
 |<-- RSP CompDBIDResp --|                       |
 |-- DAT CopyBackWrData ->|                      |
 |-- REQ <原命令> ------->|                       |
 |                       |-- REQ WriteNoSnpFull->|
 |                       |<---- RSP DBIDResp ----|
 |                       |-- DAT NonCopyBackWrData ->|
 |                       |<---- RSP Comp ---------|
```

- RN 发 `WriteBackFull`，等 `CompDBIDResp` 后把脏数据作为 `CopyBackWrData(UD_PD)` 发出。
- DAT 注入后 RN 立即发原命令，不等 SN 写完；原命令如果先到，会被 HN `RetryAck`。
- HN 等 SN 的 `Comp` 才算写回完成。

取消路径：如果 `WriteBackFull` 还没收到 `CompDBIDResp`，而 snoop 命中 victim，RN 用 `SnpRespData(I_PD)` 把数据交给 snoop 并清除 `victimDirty`；原来的写回继续，但 `CopyBackWrData` 带 `resp = I` 和全零数据，HN 丢弃、不写 SN。

### 4.9 场景 8：两个 RN 争用 HN

RN0 正在写回另一行的脏 victim，RN1 发来写 miss 的 `ReadUnique`。HN 只有一个活动上下文，于是：

```text
RN1                         HN
 |-- REQ ReadUnique(allowRetry=1) -->|
 |<-- RSP RetryAck ------------------|
 |                                   |  （HN 完成 RN0 的写回）
 |<-- RSP PCrdGrant -----------------|
 |-- REQ ReadUnique(allowRetry=0) -->|
 |<-- 正常事务 ---------------------->|
```

- 第一次请求被存入等待表，HN 回 `RetryAck`。
- HN 空闲后把等待项搬进上下文并回 `PCrdGrant`。
- RN1 收齐 `RetryAck` 和 `PCrdGrant`（顺序任意）后，用相同身份重发，`allowRetry = 0`。
- HN 对 credited retry 不能再拒绝。

这就是两个 RN 共享一个 HN 时的排队方式：不是让请求停在环上，而是协议层拒绝后重新授权。

### 4.10 参考：HN 访问 SN 与 snoop 响应

HN 访问 SN 只有两种请求：

```text
读：
HN  -- REQ ReadNoSnp ---->  SN
HN  <-- DAT CompData(I) --  SN

写：
HN  -- REQ WriteNoSnpFull ->  SN
HN  <-- RSP DBIDResp --------- SN
HN  -- DAT NonCopyBackWrData -> SN
HN  <-- RSP Comp -------------- SN
```

- 下游 `txnID` 恒为 0；`CompData` 的 `dbID` 为 0。
- `DBIDResp` 只是 SN 的数据槽授权；`Comp` 才表示内存更新完成。
- 上游给 RN 的写授权是 `CompDBIDResp`，与 SN 的 `DBIDResp` 是两套独立握手。

snoop 只由 HN 发往 RN，不经过 SN。RN 的响应：

| 本地情况 | `SnpShared` | `SnpUnique` |
|---|---|---|
| cache 未命中 | `SnpResp(I)` | `SnpResp(I)` |
| 命中 SC 或 UC | `SnpResp(SC)`，变 SC | `SnpResp(I)`，变 I |
| 命中 UD | `SnpRespData(SC_PD)` + 数据，变 SC | `SnpRespData(I_PD)` + 数据，变 I |
| 命中待写回的脏 victim | `SnpRespData(I_PD)` + 数据 | 同左 |

RN 有独立的 snoop 响应缓冲，snoop 处理与本地事务并行。即使本地命令在等数据、或者本地结果被背压，RN 也必须能收 snoop 并回复。snoop 响应在端点内部优先于本地 RSP/DAT；但一旦某条本地消息已经在停滞状态下呈现给端口，它会被锁定到握手完成，不会被 snoop 顶掉。

---

## 5. 完整例子：写 miss + 脏替换 + 跨核 snoop

场景：RN0 写 miss，同索引有脏 victim；目标行由 RN1 以 UD 持有。

| 步骤 | 消息 | 通道 | 路径（经过的 stop） | 跳数 |
|---:|---|---|---|---:|
| 1 | `WriteBackFull` | REQ | RN0 → HN | 1 |
| 2 | `CompDBIDResp` | RSP | HN → RN1 → SN → RN0 | 3 |
| 3 | `CopyBackWrData`（脏数据） | DAT | RN0 → HN | 1 |
| 4 | `ReadUnique`（原命令，`allowRetry=1`） | REQ | RN0 → HN | 1 |
| 5 | `RetryAck` | RSP | HN → RN1 → SN → RN0 | 3 |
| 6 | `WriteNoSnpFull`（HN 写回 victim） | REQ | HN → RN1 → SN | 2 |
| 7 | `DBIDResp` | RSP | SN → RN0 → HN | 2 |
| 8 | `NonCopyBackWrData` | DAT | HN → RN1 → SN | 2 |
| 9 | `Comp`（victim 写回完成） | RSP | SN → RN0 → HN | 2 |
| 10 | `PCrdGrant` | RSP | HN → RN1 → SN → RN0 | 3 |
| 11 | `ReadUnique`（`allowRetry=0`） | REQ | RN0 → HN | 1 |
| 12 | `SnpUnique` | SNP | HN → RN1 | 1 |
| 13 | `SnpRespData(I_PD)` + 脏数据 | DAT | RN1 → SN → RN0 → HN | 3 |
| 14 | `CompData(UD_PD)` + 数据 | DAT | HN → RN1 → SN → RN0 | 3 |
| 15 | `CompAck` | RSP | RN0 → HN | 1 |

这里展示的是原命令恰好落在 HN 忙碌期间的竞争路径。如果 `ReadUnique` 在 HN 完成写回并回到 `idle` 之后才到达，就不会出现步骤 5 和 10，HN 直接接纳原命令。

关键因果：

1. RN0 先处理 victim，发 `WriteBackFull`；此时原命令还没发出。
2. HN 回 `CompDBIDResp`，RN0 发 `CopyBackWrData`。**DAT 注入后 RN0 立即恢复原命令**。
3. 原命令 `ReadUnique` 到达时 HN 仍在处理写回，HN 把它放进等待表并回 `RetryAck`。
4. HN 完成写回（步骤 6–9）后空闲，把等待项搬进上下文，发 `PCrdGrant`。
5. RN0 重发 `ReadUnique`，HN 查目录发现 RN1 持有，发 `SnpUnique`；RN1 回脏数据 `I_PD`。
6. HN 不写 SN，直接把数据作为 `CompData(UD_PD)` 转交 RN0；RN0 安装本地写数据为 UD，回 `CompAck`。

15 条消息中 REQ 4、RSP 6、SNP 1、DAT 4，分别走四条独立环。步骤 5 的 `RetryAck` 与步骤 6 的 `WriteNoSnpFull` 可以同时在途。

---

## 6. 完成边界

| 操作 | RN 本地结果 | HN 释放上下文 | 内存更新 |
|---|---|---|---|
| 读/写 miss | 收到 `CompData` 并安装；注入 `CompAck` 后返回 | 收到 `CompAck` | 取决于是否触发写回 |
| 干净替换 | 收到 `Comp` 后继续原命令 | `Comp` 注入后 | 不涉及 |
| 脏替换 | 写回 DAT 注入后继续原命令 | 收到 SN 的 `Comp`，或取消数据被消费 | SN 执行写回数据时 |

三条结论：

1. 本地完成不等于内存已更新。
2. `CompDBIDResp`/`DBIDResp` 只是数据槽授权，不是写完成。
3. 一致事务的 HN 边界在 `CompAck`；本地结果背压不会延长 HN 的上下文生命周期。

---

## 7. 并发、阻塞与进展

- **端点串行**：RN 一个本地命令，HN 一个活动上下文，SN 一次内存操作。
- **重试是协议层排队**：HN 用等待表接纳被拒绝的首次请求，空闲后轮转授权。
- **通道独立**：REQ 被占用时，SNP/DAT/RSP 仍可流动；这是 snoop 能在本地事务等待时继续工作的前提。
- **转发绝对优先**：环不保证注入公平性；持续转发流量下本地注入可能长期等待。这是有意的取舍。
- **bubble 保证可移动空间**：注入至少保留一个下游空位，避免环被填满后锁死。
- **进展假设**：有限的外部流量，且下游最终接受。没有延迟或公平性保证；测试中的等待上界是断言，不是性能承诺。
- **snoop 不能被本地背压挡住**：RN 即使本地事务或结果停滞，也必须能收 snoop 并回复，否则 HN 会永久等待。

---

## 8. 微架构取舍：硬件式部分与刻意的串行化

这个实现首先是协议子集的功能模型，而不是性能导向的微架构。传输层是常规 RTL，端点层则有多处刻意串行化、偏“软件式建模”的结构。把它们分开看，有助于判断哪些是范围选择，哪些是以后要改的。

### 8.1 传输层是常规硬件写法

- `RingStop` 用 FIFO + 转发优先仲裁 + credit/bubble：转发需要 1 个下游空位，注入需要 2 个（`RingStop.scala:19-49`）。队头阻塞被保留，不做旁路。
- 四条通道各有一条独立单向环，用物理资源换通道级并发；REQ 阻塞不会拖住 RSP/SNP/DAT。
- 所有边界都是显式 `Decoupled` 握手，没有隐藏的阻塞调用。
- RN 有独立的 snoop 响应路径，本地事务等待或结果背压时仍能回 snoop（`RnNode.scala:284-355`）。
- 路由是静态的：`ChiNoC` 把 `tgtID` 译成物理 `dst`（`ChiNoC.scala:27-33`），所有缓冲有界，没有动态分配。

这些部分的设计方式与真实硬件一致。

### 8.2 端点层的软件式痕迹

1. **单线程事务模型**：每个端点只有一个活动上下文，其余请求被 `RetryAck` 进等待表，空闲后再授权。这等价于“单线程服务器 + 等待队列”，而不是多 outstanding 事务。HN 的 14 个状态中大量是 `wait*` 阻塞阶段（`HnNode.scala:35-37`）。
2. **store-and-forward**：HN 把 SN 返回的整行 512 bit 存进 `data` 寄存器，再重新发一条 `CompData` 给 RN（`HnNode.scala:381`、`436-456`），而不是直通转发。脏 snoop 数据同样先落入 HN。没有 DMT。
3. **写路径串行化**：对脏写回，HN 先回 `CompDBIDResp`，等 RN 的 `CopyBackWrData` 到达后才向 SN 发 `WriteNoSnpFull`（`HnNode.scala:318-382`、`261-287`）。这不仅是风格问题：数据到达前 victim 仍可能被 snoop 抢走，HN 必须能选择不写内存（见 4.8）。若要提前发下游请求，需要额外的写回中止机制。
4. **重试表是软件等待队列**：把整条 `ReqFlit` 当作“请求对象”保存，在槽位间搬运，授权时再整体搬进事务上下文（`HnNode.scala:63-88`、`246-254`、`468-477`）。重复检测和选槽在默认 2 槽时是小规模并行比较/优先级逻辑，结构本身没问题；软件式的地方是“保存对象、调度、再执行”的模型。槽位增多时，这种整对象保存会明显变得不划算。
5. **用寄存器数组模拟存储器**：SN 的 memory 是 `RegInit(VecInit(initialLines))`，读为组合逻辑一拍出数（`SnNode.scala:29-31`、`84-87`）；没有 SRAM 宏、读延迟或初始化流程。cache 的有效位/状态位和目录条目用 `RegInit` 数组复位初始化，cache 数据和标签则不复位。
6. **长组合链与隐式优先级**：`reqRx.ready` 同时依赖状态、等待槽、待发 `RetryAck` 和 `reservedRetry`（`HnNode.scala:181-184`）；重试选择和空闲槽分配用 Scala `for` 生成优先级逻辑，目录更新则对两个槽位做展开比较；`ChiNoC.route` 是线性 `MuxCase`。4 个节点、2 个槽位时综合没有问题，但代码里没有 pipeline register 或时序边界的意识。

### 8.3 为什么在当前范围内可以接受

项目的范围是功能正确性和事务完成语义，不是延迟、面积或频率。上述选择让不变量容易陈述和验证：单一活动上下文使目录和所有权状态没有并发交叉；store-and-forward 让数据归属和完成顺序显式化；阻塞式 FSM 与断言、参考模型直接对应；复位初始化数组让测试结果确定。

代价同样明确：多段往返被串行化，HN 和 SN 各自持有 512 bit 数据寄存器，事务之间没有重叠。

### 8.4 如果转向性能导向

按收益/改动量排序：

1. **重叠写回路径**：为脏写回引入显式的取消/中止机制后，让 HN 在等 RN 数据时提前向 SN 申请授权；否则保留当前“等数据到达”的顺序。
2. **拆分 HN 状态机**：按通道拆成 REQ/RSP/DAT/SNP 控制器加事务 scoreboard，允许阶段重叠并缩短组合路径。
3. **多事务上下文**：每个 RN 允许多个 outstanding，按 `txnID` 索引，替代“一笔 + 等待表”。
4. **数据直通**：读数据和 snoop 脏数据经 HN 转发，不再整行缓存；进一步可做 DMT。
5. **存储器宏化**：SN 用 SRAM 模型和读延迟，cache/目录只复位 valid 位。
6. **重试表瘦身**：只存身份字段；槽位增多时改用 CAM/并行比较。

这些是范围变化，不是缺陷修复。在当前“每端点一笔 + 功能正确性优先”的约束下，8.2 的写法是自洽的。

---

## 9. 未实现的 CHI 能力

- 部分写 / 字节掩码；多 beat；非 64 字节行。
- DMT、forwarded snoop、RN↔RN 直接数据传输。
- `ReadOnce`、`ReadClean`、`ReadNotSharedDirty`。
- `MakeUnique`、`CleanUnique`、`MakeInvalid`；升级和失效只通过 `ReadUnique` + snoop。
- `WriteUnique*`、`WriteCleanFull`、`WriteEvictFull`。
- `SnpOnce`、`SnpClean`、`SnpCleanShared`、`SnpMakeInvalid`。
- `WriteDataCancel`；取消通过带 `I` 的 `CopyBackWrData` 表达。
- CHI 链路层、错误恢复、poison、DVM。
- 目录淘汰；目录必须覆盖 SN 内存。

---

## 10. 源码地图

| 内容 | 文件 |
|---|---|
| opcode / resp 编码 | `src/main/scala/chi/ChiOpcode.scala` |
| flit 字段 | `src/main/scala/chi/ChiFlit.scala` |
| 四通道端口 | `src/main/scala/chi/ChiPort.scala` |
| ring stop：FIFO、仲裁、credit | `src/main/scala/noc/RingStop.scala` |
| 四条环组装 | `src/main/scala/noc/QuadRing.scala`、`Ring.scala` |
| CHI ID → 物理位置 | `src/main/scala/chi/ChiNoC.scala` |
| RN 状态机、cache、替换、snoop | `src/main/scala/chi/RnNode.scala` |
| HN 目录、snoop、重试、下游转换 | `src/main/scala/chi/HnNode.scala` |
| SN 内存与写完成 | `src/main/scala/chi/SnNode.scala` |
| 系统组装与物理顺序 | `src/main/scala/chi/ChiSystem.scala` |

状态机规模：RN 8 态，HN 14 态（含 `sendGrant`/`waitRetryReq`），SN 9 态。读代码时重点看：

- `RnNode.resumeRequest()` 如何把"victim 请求"与"原命令"分成两笔，并重置重试状态。
- `HnNode` 的 `reqRx.ready` 三个分支：idle、活动事务接纳重试、`waitRetryReq` 等待 credited retry。
- `HnNode` 在 `waitSnComp` 收到 `Comp` 后，如何区分 `snoopWrite` 与普通写回。
- `SnNode` 的 `sendDbId` 与 `sendComp` 分离，对应"授权"和"完成"两个语义。
- `RingStop` 的 `canForward`/`canInject` 阈值是 bubble 论证的核心。

更完整的架构说明见 [design.md](design.md)。
