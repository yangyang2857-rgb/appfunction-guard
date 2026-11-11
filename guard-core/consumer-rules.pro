# 保留注解元信息（运行时需要读取）
-keepattributes *Annotation*
-keep @io.github.yangyang2857.afguard.Guard class * { *; }
-keep @io.github.yangyang2857.afguard.RateLimit class * { *; }

# 生成的校验器需要被反射之外的直接调用，保持类名可见即可
-keep class io.github.yangyang2857.afguard.generated.** { *; }
