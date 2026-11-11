# 给 Android 的 AI 后门装锁：第一周踩坑记录

> 项目：[appfunction-guard](https://github.com/yangyang2857-rgb/appfunction-guard)（MIT，alpha）
> 结论先放这里：**7 天里真正花时间的不是写拦截逻辑，而是四类"编译通过、运行静默失效"的坑。**

## 一、起因不是技术问题，是权限问题

Android 16 的 AppFunctions 让 AI Agent 可以直接调用 App 里的函数，不经过 UI。平台把管道给齐了：注册函数、向模型描述函数、执行函数。

管道齐了，但阀门没装。平台没有回答这几个问题：

- 同一个 App 里，`getNote` 开放给 Agent 了，`deleteAllNotes` 是不是也顺手开放了？
- 模型幻觉出 `limit = -1`，你的代码会不会照单全收？
- Agent 的重试循环会不会在一分钟里打你数据库 4000 次？
- 用户问"昨晚那个助手对我的数据做了什么"，你拿什么回答？
- Agent 拿到 `IllegalArgumentException` 之后会怎么做？大概率是原地重试。

这五个问题不是缺 API，是缺一层策略。所以我写了 [AppFunction Guard](https://github.com/yangyang2857-rgb/appfunction-guard)：一个放在平台调用与业务代码之间的拦截链。

```
业务代码 → AfGuard.intercept(functionId, args) { ... }
              ├─ 1. 调用方白名单
              ├─ 2. 策略判定（ALWAYS_DENY / CONFIRM_EACH / CONFIRM_ONCE / ALLOW）
              ├─ 3. 限流（令牌桶，按调用方）
              ├─ 4. 参数校验（KSP 编译期生成）
              ├─ 5. 用户确认（只在策略要求时）
              ├─ 6. 执行你的代码
              ├─ 7. 返回值脱敏
              └─ 8. 审计（只记参数指纹）
```

顺序是有讲究的：策略在限流前面，被永久禁用的函数不该消耗配额；限流在校验和确认前面，已经被限流的调用不该再去烦用户点弹窗。

下面是一周里真正踩到的东西。按"浪费的时间"排序。

---

## 二、坑 1：KSP 生成的类没继承接口，编译期一切正常

这是第一个坑，也是最贵的。`guard-core` 通过反射拿 KSP 生成的表：

```kotlin
private fun loadGenerated(): GeneratedGuardTable? = try {
    val clazz = Class.forName("io.github.yangyang2857.afguard.generated.AfGuardGeneratedValidators")
    clazz.getField("INSTANCE").get(null) as? GeneratedGuardTable
} catch (t: Throwable) {
    null   // 没接 KSP 是合法用法，静默降级
}
```

问题在于处理器生成的类当时长这样：

```kotlin
public object AfGuardGeneratedValidators : FunctionValidator { ... }
```

而 `MetadataRegistry` 要的是 `GeneratedGuardTable`（`FunctionValidator` + `metadataOf`）。接口对不上，`as?` 直接返回 `null`。

后果不是崩溃，是**更糟的东西**：

- `AfGuard.intercept()` 照常工作，不报错；
- 策略、限流、脱敏全部按默认策略走；
- 你标了 `ALWAYS_DENY` 的函数，实际行为变成了"问用户一次"；
- 参数校验整个不生效。

一个安全库在失效时表现成"正常工作"，这是最坏的设计。修法两条：

1. 生成类直接实现 `GeneratedGuardTable`（它本身继承 `FunctionValidator`，一个接口就够）；
2. **降级必须留痕**——现在每条审计记录带 `degraded = true`，界面上直接显示"校验器未生效"。宁可显得难看，也不能装作没事。

教训：**任何"失败即降级"的设计，都必须有一个可见的降级信号**，否则它迟早会在生产上悄悄关掉自己。

---

## 三、坑 2：审计出口没挂上，日志全是空的

`GuardConfig.init(context)` 里做了一件事：

```kotlin
fun init(context: Context) {
    appContext = context.applicationContext
    AuditLog.install(auditSink)   // 就这一行
}
```

第二天我把 `auditSink` 换成了自定义实现，记录开始丢。原因很直白：`auditSink` 是个可写的全局变量，改完它不会自动重新 `install`。审计出口是在 `init` 那一刻绑定的，之后换出口必须补一次 `GuardConfig.apply()`。

为什么这个坑值得写：它的表现是"审计功能好像在工作"——`RingBufferAuditSink` 里确实有记录，但那是 `init` 时挂上的默认实例，你后来换的那个一条都没有。如果你把审计上报到服务端，这个 bug 会在上线后一周才被发现。

现在 `SampleApp` 里的顺序是硬约束：

```kotlin
GuardConfig.init(this)                    // 1. 先 init
GuardConfig.auditSink = MySink()          // 2. 换出口
GuardConfig.apply()                       // 3. 必须补这一行
```

**给可变全局单例配一个"必须手动同步"的隐藏契约，是设计缺陷。** 更好的做法是 `install` 时直接持有配置对象引用、每次审计从配置读一次 sink，但那样每条记录多一次 volatile 读。我选择了保留这个坑，并在文档里用加粗标出来——因为性能敏感的拦截链上，我不想为了一次配置错误买单。

---

## 四、坑 3：token 只有 `repo` scope 时，GitHub 返回的是 404

这条和 Android 无关，但它是本周查得最久的一个。

我想用 REST API 把 CI 配置推到 `.github/workflows/build.yml`。返回：

```
404 Not Found
```

第一反应是仓库路径写错了，第二反应是 token 过期，第三反应是分支保护。全都不是。

真实原因：**GitHub 要求写 `.github/workflows/*` 的凭据必须额外持有 `workflow` scope**，而 classic token 默认只勾了 `repo`。权限不足时它返回 404 而不是 403——因为对无权限者来说，这个路径"等于不存在"。

验证方式：

```bash
curl -sI -H "Authorization: token $GH_TOKEN" https://api.github.com/user | grep -i x-oauth-scopes
# x-oauth-scopes: repo        ← 缺 workflow
```

修法有两条：重新签发一把勾了 `workflow` 的 token，或者网页上点两下。我选了第三条——**把 workflow 文件暂存在 `ci/build.yml`，在 `docs/CI.md` 里写清 30 秒启用步骤**。

这个处理方式比硬碰更值得推荐：CI 配置需要一次性的人为授权，那就把它显式地变成一个"待授权动作"，而不是让后来 clone 的人对着一堆 404 猜哪里坏了。

---

## 五、坑 4：CI 里少了 `setup-android`，构建 100% 失败

第一版 `ci/build.yml` 长这样：

```yaml
- uses: actions/checkout@v4
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: '17' }
- uses: gradle/actions/setup-gradle@v4
  with: { gradle-version: '8.13' }
- run: gradle assembleDebug
```

看起来很完整。但 `ubuntu-latest` 镜像**不带 Android SDK**。没有 `local.properties`、没有 `ANDROID_HOME`，AGP 会在配置阶段就报 `SDK location not found`。

补上：

```yaml
- uses: android-actions/setup-android@v3
- name: Accept SDK licenses
  run: yes | sdkmanager --licenses > /dev/null || true
```

另外两个细节：

- `gradle-version: '8.13'` 不是随便填的，AGP 8.13 要求 Gradle ≥ 8.13。仓库里没有 wrapper（生成 wrapper 需要先有一次本地 Gradle），所以只能靠 `setup-gradle` 拉发行版，命令得写 `gradle` 而不是 `./gradlew`。README 里我一开始就写错了，写成了 `./gradlew :sample:installDebug`——一个不存在的文件。
- 顺手加了 `upload-artifact` 传 sample 的 APK。**不能本地编译的项目，CI 产物就是唯一的验证手段**，光看"绿了"不够，得能把 APK 拉下来装到手机上。

---

## 六、坑 5：确认弹窗绝对不能在主线程等

这条是设计层面的，但它是整个库里最容易接错的地方，值得单独说。

`ConfirmationHandler` 是个同步回调：

```kotlin
fun interface ConfirmationHandler {
    fun confirm(request: ConfirmRequest): Boolean
}
```

宿主要在一个函数调用里返回"用户点没点同意"。而 `AlertDialog` 必须在主线程创建。于是最自然的写法是错的：

```kotlin
// 错误：如果这段跑在主线程，弹窗永远等不到结果
val granted = handler.confirm(request)
```

主线程等 latch，弹窗要主线程才能显示——死锁。Agent 的调用线程大概率是 Binder 线程，不会死锁，但只要你哪天在主线程里手动调一次 `AfGuard.intercept()`（做测试很容易这样），就会卡死，而且卡得没有任何日志。

正确写法只有一个：**调用方在后台线程，确认回调里把 UI 操作 post 到主线程，用 latch 把两边接起来**。

```kotlin
override fun confirm(request: ConfirmRequest): Boolean {
    val host = activity ?: return false          // 没有可见界面 = 拒绝
    val latch = CountDownLatch(1)
    val granted = AtomicBoolean(false)

    host.runOnUiThread {
        AlertDialog.Builder(host)
            .setPositiveButton("允许") { _, _ -> granted.set(true); latch.countDown() }
            .setNegativeButton("拒绝") { _, _ -> latch.countDown() }
            .show()
    }
    return latch.await(request.timeoutMillis, TimeUnit.MILLISECONDS) && granted.get()
}
```

`request.timeoutMillis` 缺省 15 秒（示例里调到 20 秒）。超时按拒绝处理，理由是：**Agent 的等待预算比用户的注意力更廉价**。宁可让它拿到 `CONFIRMATION_TIMEOUT` 重试一次，也不能让 Binder 线程挂在那里等一个已经去吃饭的用户。

---

## 七、一个我认为最值得抄的设计：`retryable`

整篇文章如果只留一段代码，留这个。

```json
{
  "afg_error": {
    "code": "RATE_LIMITED",
    "function": "deleteNote",
    "retryAfterMs": 12000,
    "retryable": true,
    "hint": "Too many calls. Wait for retryAfterMs, then retry."
  }
}
```

Agent 的默认行为是"失败了再来一次"。你给它一个异常字符串，它没法判断该不该重试，于是要么疯狂重试，要么直接放弃——两种都很糟。

`retryable` 是个布尔值，但它直接掐断了 LLM 的重试循环：

| code | retryable | Agent 应该做什么 |
|---|---|---|
| `INVALID_ARGUMENT` | yes | 改参数，按 `expected` 重试 |
| `RATE_LIMITED` | yes | 等 `retryAfterMs` |
| `USER_DENIED` | **no** | 人类说不行。停。 |
| `FUNCTION_BLOCKED` | **no** | 永久关闭，别再试 |
| `UPSTREAM_ERROR` | **no** | App 自己的代码坏了，别猛敲 |

同时审计记录里刻意**不存参数原文**，只存参数"形状"的 SHA-256：

```kotlin
private fun digest(args: Bundle): String {
    val sb = StringBuilder()
    for (key in args.keySet().sorted()) {
        sb.append(key).append(':').append(args.get(key)?.javaClass?.simpleName).append(';')
    }
    return MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
}
```

一个安全库如果自己记录用户的原始消息内容，它就是一个新的漏洞，而不是修复。

---

## 八、这周没做到的事，写在 README 里

诚实比体面重要。

- **没有在真机上跑过。** 构建环境里没有 Android SDK，也没有 JDK。所有代码是"写出来、推上去、等 CI 第一次报错"，不是"跑通了再推"。这也是为什么 `README` 的 Status 里那一条 `[ ] End-to-end verification` 我留着没勾。
- **CI 还没启用。** 卡在 `workflow` scope 上，需要一次网页操作。`docs/CI.md` 里写了两条路径。
- **`functionId` 用的是函数简单名。** 两个类里都有 `deleteNote` 会互相覆盖。这个限制目前只写在 `sample/README.md` 的"已知限制"里，没做处理——正确处理要么要求全限定名，要么在编译期检测冲突并报错，后者是下一周的事。
- **`guard-ui`、`guard-cli`、Maven Central 发布**都还没开始。
- **README 徽章一开始写错了**：`minSdk 36`，而 `guard-core` 实际是 26。库本身只用 `Bundle` + `Binder`，26 就能跑；36 是 AppFunctions 的要求，不是这个库的要求。这种"顺手写的数字"最容易错，而且没人会去校验徽章。

## 九、下一周

1. 在 Android 16 设备/模拟器上跑 `sample`，把真实的失败日志写进第二轮记录；
2. KSP 编译期检测 `functionId` 冲突；
3. `guard-ui`：一个可直接用的确认 Sheet，把上面那个 latch 的写法封装掉，让人不用再自己踩一次。

如果你也在做 AppFunctions 相关的东西，或者认为这个方向不值得做，都欢迎来 issue 里说。**在 alpha 阶段，对注解设计的反驳比 bug report 有用得多。**

---

*仓库：[github.com/yangyang2857-rgb/appfunction-guard](https://github.com/yangyang2857-rgb/appfunction-guard) · MIT*
