# SOA++ —— 数据导向实体优化（Fabric 1.20.1）

> **SOA++**（Mod ID：`soatick`，原名 SoA Tick）——一个鲜有人尝试的方案：
> 把 Minecraft 实体从「面向对象」重构为「数据导向」。
> 实体热字段镜像进连续数组（Structure of Arrays），Tick 与渲染决策全部退化为
> **纯数值数组扫描**，把 CPU 缓存命中率与单核性能压榨到极致。

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green) ![Fabric](https://img.shields.io/badge/Fabric-Loader%20%E2%89%A50.15.0-yellow) ![Java](https://img.shields.io/badge/Java-17-orange) ![License](https://img.shields.io/badge/License-MIT-blue)

> **v0.2.0 新增**：物品硬跳过（消失计时按真实速率快进）· AI 降级档位（不思考但会走动）·
> 碰撞推挤跳过 · 远景掉落物自动合并 · 维度分桶 · 增量环更新 · 客户端位置平滑（消除量子移动）·
> 异步遮挡剔除 · LOD 名牌/影子降级 · 每实体类型规则 · `/soa top|toggle|ring` 命令 ·
> 服务端配置同步 · ModMenu 图形配置（Cloth Config）· MSPT 自测

- **目标版本**：Minecraft `1.20.1` · Fabric Loader `≥0.15.0` · Java 17
- **环境**：客户端 / 服务端 / 单人，均可运行
- **依赖**：Fabric API；**Sodium 正交兼容**（详见下文）
- **移动端**：纯逻辑零 shader，内存占用约 3 MB，默认参数按 PojavLauncher（ARM 大核）调优

---

## 1. 为什么做数据导向（SoA）？

### 原版的痛点：AoS（Array of Structs）

原版每个实体是一个巨大的 Java 对象：坐标、速度、血量、AI、碰撞箱、
dataTracker、乘客引用……几十个字段挤在同一个对象里，对象之间还通过
`world` / `passengers` / `section` 相互引用。

实体 Tick 与渲染时，CPU 其实只需要回答几个**标量问题**：

- 这只怪离最近玩家多远？→ 该不该满速 Tick？
- 这个实体离相机多远？→ 该不该渲染？

但在 AoS 布局下，为了读一个 `x`，CPU 必须沿指针把整个对象（乃至对象图）
拖进缓存——一条 64 字节的缓存行里真正用到的往往只有 8 字节，
**缓存行利用率不足 15%**。实体一多（刷怪塔、大型服务器、百人战场），
单核就被「指针追逐 + 缓存未命中」活活拖死。

### SoA：按字段拆列

SoA Tick 把所有实体「决策要用」的热字段拆出来，按**字段为维度**放进
连续的 primitive 数组：

```text
 x[]  = [ e0.x | e1.x | e2.x | e3.x | ... ]   连续 double，顺序扫描零失配
 y[]  = [ e0.y | e1.y | e2.y | e3.y | ... ]
 z[]  = [ e0.z | e1.z | e2.z | e3.z | ... ]
 flags[]  = [ 存活|玩家|Boss|载具|拴绳... ]    一次位与完成豁免判断
 ring[]   = [ 近 | 中 | 远 | 极远 ]            上一次 Pass 的分类结果
 category[] = [ 生物|物品|经验|投射物|杂项|Boss ]
 visible[]  = [ 1 | 0 | 1 | 1 ]               客户端迟滞可见状态
```

批量遍历（如「给 2000 只怪算最近玩家距离」）变成对三个 double 数组的
**顺序扫描**——硬件预取器完美工作，缓存命中率接近 100%。
2000 实体 × 20 玩家的距离计算 ≈ 4 万次浮点乘加，在现代 CPU 上
**远低于 0.1 ms**；而原版等价逻辑要沿 2000 个对象指针各自追逐。

### 架构总览

```text
 ┌───────────────────────  写透层（Mixin，成本≈0） ───────────────────────┐
 │ Entity.setPos/setVelocity  ──TAIL──▶  位置/速度 → SoA 对应槽位        │
 │ LivingEntity.setHealth     ──TAIL──▶  血量   → SoA 对应槽位           │
 │ Entity.baseTick            ──HEAD──▶  懒分配槽位 + 每 tick 快照刷新   │
 │ Entity.remove              ──HEAD──▶  槽位 O(1) 回收                 │
 └───────────────────────────────────────────────────────────────────────┘
                                      │  （实体数据本来就热在缓存里，
                                      │    写透只是顺手写几个数组元素）
                                      ▼
 ┌──────────────────────  SoA 存储（线程域分离） ────────────────────────┐
 │  ServerSoaStore  —— 服务端线程专属，无锁                              │
 │  ClientSoaStore  —— 客户端主线程专属，无锁                            │
 │  occupied[] 稠密占用列表：Pass 只遍历活跃实体，与数组容量无关          │
 └───────────────────────────────────────────────────────────────────────┘
                                      │  纯数组批量扫描（决策层）
               ┌─────────────────────┴──────────────────────┐
               ▼                                            ▼
 ┌───────────── 服务端：距离分环调度 ───────────┐  ┌───── 客户端：渲染剔除 ─────┐
 │ ServerWorld.tick HEAD：算最近玩家距离，分环  │  │ WorldRenderer.render HEAD: │
 │ tickEntity HEAD：位运算错峰，跳过非本轮实体  │  │ 算相机距离，分类+迟滞判可见 │
 │                                              │  │ shouldRender HEAD：查表短路 │
 └──────────────────────────────────────────────┘  └────────────────────────────┘
```

---

## 2. 核心机制详解

### 2.1 写透（write-through）镜像

决策要新鲜数据，但绝不能在决策时反序读对象（那就退化回 AoS 了）。
SoA Tick 的做法是**让实体自己顺手写**：

- 实体在自身 Tick 上下文里，它的字段本来就热在 L1 缓存中；
- Mixin 在 `setPos` / `setVelocity` / `setHealth` 的 TAIL、`baseTick` 的 HEAD
  打旁路钩子，把字段写进数组对应槽位——每次只是几个顺序内存写；
- 槽位号通过「鸭子接口」直接长在 Entity 对象上（一个 int 字段），
  不用 `HashMap`，避免装箱与哈希开销。

未 Tick 过的实体不分配槽位；容量耗尽的实体槽位保持 `-1`，
**所有优化自动绕行，实体走原版路径**——这是整个方案的安全网。

### 2.2 O(1) 槽位分配器

- `freeStack`：空闲槽位栈，弹出即分配；
- `occupied[]`：稠密占用列表，决策 Pass 只扫 `[0, occupiedCount)`，
  数组开得再大，成本也只与**活跃实体数**成正比；
- `occupiedIndex[]`：占用列表反查表，释放时交换删除，O(1)。

### 2.3 服务端：距离分环错峰降频

每个维度 Tick 开始时（`ServerWorld.tick` HEAD），调度 Pass 顺序扫描数组：

| 距离最近玩家 | 默认节奏 | 说明 |
|---|---|---|
| ≤ 32 格 | 满速 | 玩家眼前的实体，行为 100% 原版 |
| 32 ~ 64 格 | 1/2 速 | |
| 64 ~ 128 格 | 1/4 速 | |
| > 128 格 / 无玩家维度 | 1/8 速 | 远景实体，收益最大 |

**错峰（stagger）**：降频分母是 2 的幂，槽位号参与取模——
`((phaseTick & (div-1)) != (slot & (div-1)))` 即跳过。
同一环的实体被槽位号天然打散到不同 Tick，不会出现
「集体卡一下、集体瞬移一下」的节拍器效应。

**豁免体系**（可配置）：玩家永远满速；Boss（凋灵/末影龙）、
被拴绳生物、有名牌实体、载具与乘客默认全部强制近环——
矿车过山车、拴住的羊驼、宠物不会因为降频出戏。

### 2.4 客户端：分类渲染距离 + 迟滞防闪烁

每帧（`WorldRenderer.render` HEAD，相机已就位）批量 Pass 扫描数组：

| 分类 | 默认渲染距离 | 说明 |
|---|---|---|
| 生物 | 128 格 | |
| 掉落物 | 48 格 | 刷怪塔掉落物海是帧数杀手 |
| 经验球 | 40 格 | |
| 投射物 | 64 格 | 箭矢、雪球、火球 |
| 杂项（盔甲架/展示框/TNT/船/矿车等） | 96 格 | |
| 玩家 / Boss | 永不剔除 | |

**迟滞（Hysteresis）**：直接「超过 R 就剔除」会让临界距离上的实体
忽隐忽现。所以剔除与恢复用两个阈值：

- 可见 → 剔除：距离 **>** R（严格超出才隐藏）
- 剔除 → 可见：距离 **<** R − 8（退回缓冲带内才恢复）

在 (R−8, R) 区间内维持原状态，从机制上消除闪烁。

被剔除的实体在 `EntityRenderDispatcher.shouldRender` HEAD 直接短路：
**不构造包围盒、不做视锥测试、不取渲染器、不分配顶点缓冲**。

---

## 3. 兼容性

### Sodium

**正交兼容，可放心共存。** SoA Tick 完全不触碰区块渲染管线
（那是 Sodium 的领域），只在实体层做两件事：

1. 服务端 Tick 门禁（`ServerWorld.tickEntity`）——Sodium 不修改服务端 Tick；
2. 客户端 `shouldRender` 距离级剔除——Sodium 的视锥/遮挡剔除与我们
   的距离剔除是**叠加关系**：先被距离剔除的不进入视锥测试，
   两者各自独立生效。

### 通用兜底

- 槽位 `-1`（未追踪 / 容量耗尽）→ 优化自动绕行，走原版路径；
- 总开关 `enabled: false` → 所有 Mixin 钩子退化为空操作；
- 任何实体渲染/Tick 行为本 Mod 都「只取消、不重写」，不与
  其它同类优化 Mod 抢饭碗（如与其它 Tick 降频类 Mod 同用时，
  以配置为准，建议只启用一个）。

### 诚实的行为差异说明

数据导向调度是性能与模拟精度的权衡，以下差异**已知且有意为之**：

1. 远处实体以降频节奏更新，位置呈「量子化」移动（近处无感）；
2. 掉落物 5 分钟消失计时在降频时同步变慢（远景掉落物存留更久）；
3. 无玩家的维度整体按极远环降频；
4. 被降频实体上的「每 Tick」红石/生物机制（如远处怪的仇恨刷新）
   会以对应分母延迟。

以上均可用配置收紧（把各环距离调大、分母调小）直至接近原版。

---

## 4. 移动端（PojavLauncher）适配

- **纯逻辑、零 shader、零 GL 调用**：全部优化是内存布局与调度决策，
  不依赖任何桌面特性，ARM 芯片的 gl4es/Zink 转译层无感知；
- **内存克制**：默认 32768 + 16384 槽位，全部 primitive 数组合计
  约 3 MB（每槽位约 75 字节），可在配置里继续调低；
- **默认参数保守**：渲染剔除距离按手机屏幕可视范围标定，
  低配设备建议再把 `livingRenderDistance` 降到 64；
- **单核收益显著**：移动端 SoC 单核弱、缓存小，
  恰恰是 SoA 布局收益最大的场景。

---

## 5. 构建指南

### 环境

JDK 17+，Gradle 8.6+（或直接用 IDEA 内置 Gradle）。

### IDEA（推荐）

1. `File → Open` 打开本项目根目录，等待 Loom 导入完成（首次需联网下载依赖）；
2. 右侧 Gradle 面板 → `soa-tick → tasks → build → build`；
3. 产物在 `build/libs/soa-tick-0.2.0.jar`
   （`-sources.jar` 是源码包，不需要装进 mods）。

### 命令行

```bash
# 本仓库未内置 Gradle Wrapper 二进制，首次请先执行：
gradle wrapper --gradle-version 8.8

# 之后即可：
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

### 运行测试

`./gradlew runClient`（需先 `gradle wrapper`）或在 IDEA 直接运行
Fabric 提供的 `Minecraft Client` 运行配置。

---

## 6. 配置项全表（`config/soatick.json`）

修改后可用 `/soa reload` 热重载（槽位容量需重启生效）。

| 字段 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| **服务端** | | |
| `serverGating` | `true` | 距离分环降频开关 |
| `nearDistance` | `32` | 近环边界（格），≤ 此距离满速 |
| `midDistance` | `64` | 中环边界，1/2 速 |
| `farDistance` | `128` | 远环边界，1/4 速；超过为 1/8 速 |
| `nearDivisor` / `midDivisor` / `farDivisor` / `beyondDivisor` | `1/2/4/8` | 各环降频分母（自动钳制为 2 的幂） |
| `exemptBosses` | `true` | Boss 豁免 |
| `exemptNamed` | `true` | 有名牌实体豁免 |
| `exemptLeashed` | `true` | 被拴绳生物豁免 |
| `exemptVehiclesAndPassengers` | `true` | 载具与乘客豁免 |
| `serverMaxSlots` | `32768` | 服务端槽位上限（重启生效） |
| **客户端** | | |
| `renderCulling` | `true` | 渲染剔除开关 |
| `livingRenderDistance` | `128` | 生物渲染距离，`0` = 不限制 |
| `itemRenderDistance` | `48` | 掉落物渲染距离 |
| `xpOrbRenderDistance` | `40` | 经验球渲染距离 |
| `projectileRenderDistance` | `64` | 投射物渲染距离 |
| `miscRenderDistance` | `96` | 杂项渲染距离 |
| `bossRenderDistance` | `0` | Boss 渲染距离（`0` = 永不剔除） |
| `hysteresisBlocks` | `8` | 迟滞缓冲带宽度（格） |
| `clientMaxSlots` | `16384` | 客户端槽位上限（重启生效） |

**v0.2 新增配置键**：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `itemHardSkipFromRing` | `2` | 物品硬跳过起始环（-1 关闭；生效环内掉落物停 tick 但 itemAge 按真实速率快进） |
| `aiDegradeFromRing` | `2` | AI 降级起始环（-1 关闭；生效环内生物不思考但保留物理移动） |
| `pushSkipFromRing` | `2` | 推挤碰撞跳过起始环（-1 关闭） |
| `itemMerge` / `itemMergeRadius` | `true` / `2.0` | 远景掉落物自动合并（防实体海） |
| `entityRules` | `{}` | 每实体类型规则：`{"minecraft:villager":"exempt","某mod:*":"eighth"}`，值 exempt/half/quarter/eighth |
| `smoothDegrade` | `true` | 客户端位置平滑（多人模式经 soatick:sync 自动采用服务端阈值） |
| `lodNametags` / `lodShadows` | `true` | LOD：>48 格跳过名牌/影子 |
| `occlusionCulling` / `occlusionMinDistance` | `true` / `48` | 异步遮挡剔除（后台线程射线检测，fail-open） |

## 7. 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/soa stats` | OP(2) | 查看：槽位占用、当前维度分环分布、累计跳过/放行 Tick 数、Pass 平均耗时、本帧剔除数 |
| `/soa reload` | OP(2) | 热重载配置文件 |
| `/soa top` | OP(2) | 实体类型数量排行（近/中/远/极远分布），定位刷怪泛滥 |
| `/soa toggle <feature>` | OP(2) | 实时开关功能项并写回配置（10 项：serverGating/itemHardSkip/aiDegrade/pushSkip/itemMerge/renderCulling/smoothDegrade/occlusionCulling/lodNametags/lodShadows） |
| `/soa ring <selector>` | OP(2) | 查询单个实体的槽位/分环/距离/规则覆盖 |

## 8. 项目结构

```text
soa-tick/
├── build.gradle / settings.gradle / gradle.properties
└── src/main/
    ├── java/dev/soatick/
    │   ├── SoaTick.java                 # 主入口：生命周期 + 命令注册
    │   ├── SoaTickClient.java           # 客户端入口：断线清理
    │   ├── core/
    │   │   ├── SoaStore.java            # ★ SoA 数组 + O(1) 分配器 + 快照刷新
    │   │   ├── ServerSoaStore.java      # 服务端线程域存储
    │   │   ├── ClientSoaStore.java      # 客户端线程域存储
    │   │   ├── SoaWrite.java            # 写透路由器（Mixin 统一入口）
    │   │   ├── SoaDuck.java             # 实体槽位鸭子接口
    │   │   └── SoaFlags.java            # 位标志 / 环号 / 分类常量
    │   ├── config/SoaConfig.java        # JSON 配置（Gson，零依赖）
    │   ├── server/ServerSoaScheduler.java # ★ 距离分环 Pass + 错峰门禁
    │   ├── client/ClientSoaPass.java    # ★ 剔除 Pass + 迟滞状态机
    │   ├── command/SoaCommands.java     # /soa stats | reload
    │   └── mixin/
    │       ├── EntityMixin.java         # baseTick/setPos/setVelocity/remove
    │       ├── LivingEntityMixin.java   # setHealth
    │       ├── ServerWorldMixin.java    # tick HEAD + tickEntity 门禁
    │       └── client/
    │           ├── WorldRendererMixin.java        # 每帧批量 Pass 挂点
    │           └── EntityRenderDispatcherMixin.java # shouldRender 门禁
    └── resources/
        ├── fabric.mod.json / soatick.mixins.json
        └── assets/soatick/ (icon.png, lang/en_us.json, lang/zh_cn.json)
```

## 9. 性能：服务端实测与预期

### 9.1 服务端实测数据（专用服务端 + 协议机器人压测）

测试环境：Fabric 专用服务端 1.20.1、超平坦、`view-distance=6`，
数据包一次性召唤 **335 个实体**（4 环 × 60 NoAI 僵尸 + 盔甲架 + 掉落物），
mineflayer 协议机器人进服执行并采集 `/soa stats`：

| 指标 | 实测值 | 说明 |
|---|---|---|
| 命令执行 | 335/335 ✓ | 数据包 `function soatest:spawn` 全部生效 |
| 槽位分配 | 447，近环 121 | = 60 近环僵尸 + 60 掉落物 + 机器人本体，**与理论值精确吻合** |
| 槽位回收 | 447 → 405 → 379 | `/kill` 后 O(1) 逐步回收，含掉落物顶替，数字可解释 |
| 跳过/放行 | 118,532 / 55,117 | 持续累积，分环降频稳定生效 |
| 批量 Pass 耗时 | **0.6 ~ 0.77 µs** | 400+ 实体场景，决策层开销可忽略 |
| 异常 | 0 | 全程无报错 |
| `/soa reload` | ✓ | 热重载生效 |

### 9.1.1 v0.2.0 A/B 与同装实测（新增）

同一 1100 实体压测场景、同一硬件、15 秒采样窗（JFR 计量服务端进程 CPU）：

| 配置 | 15s CPU ticks（1500=满1核） | 相对基线 |
|---|---|---|
| SOA++ 关闭 | 885 | — |
| SOA++ 开启 | **494** | **-44.2%** |

开启态 MSPT 自测（/soa stats）：均值 5.8~8.4 ms，TPS 全程锁定 20.0；
与 Lithium 0.11.4 同装后进一步降至 **3.3~7.0 ms**——算法优化（Lithium）
与调度砍伐（SOA++）叠加生效，实测互补兼容，零异常。

> 诚实说明：该压测用 NoAI 僵尸（AI 成本本就近零），验证的是**调度正确性与
> 决策层开销**，不是 MSPT 端到端收益；真实收益场景是 AI 密集实体群
> （刷怪塔、大型牧场、长寿命服务器）。客户端渲染剔除需真实客户端验证。

### 9.2 预期与自行测量

- 调度 Pass：2000 实体 × 20 玩家 ≈ **< 0.1 ms / tick**（桌面级）；
  `/soa stats` 中的「调度批量 Pass 平均耗时」即为实测值；
- Tick 节省：刷怪场场景（>128 格实体占比高）理论跳过量
  接近 `1 - (近环实体数 / 总实体数)`，`/soa stats` 的
  「累计跳过/放行」实时反映；
- 渲染：本帧剔除数与剔除 Pass 耗时见 `/soa stats` 客户端行。
  与 Sodium 同开时，距离剔除发生在视锥剔除之前，二者收益叠加。

建议的对比测量：同一存档同一位置，`/soa stats` 记录跳过量，
配合 Spark / profiler 对比开关前后的 `Server Thread` 与
`Render Thread` 占比。

## 10. 许可证

MIT License。基于原版反编译的研究与 Mixin 实践，仅供学习交流。
