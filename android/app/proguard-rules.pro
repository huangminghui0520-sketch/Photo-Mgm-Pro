# 数据模型（algorithm 模块，反射/序列化需要）
-keep class algorithm.model.** { *; }
-keep class algorithm.ParsedLog { *; }
-keep class algorithm.ClassifyResult { *; }
-keep class algorithm.CandidatePhoto { *; }

# Kotlin 协程（R8 一般能自动处理，保留兜底）
-keepclassmembers class kotlinx.coroutines.** { *; }

# 保留行号便于调试
-keepattributes SourceFile,LineNumberTable

# 允许删除未使用代码
-dontwarn androidx.compose.**
-dontwarn coil.**
