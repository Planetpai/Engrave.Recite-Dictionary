# Add project specific ProGuard rules here.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 开源项目：保留类名（不混淆），仅做裁剪与优化，崩溃堆栈可读
-dontobfuscate

# Room：实体/DAO/数据库类在运行时经反射访问
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.TypeConverter { *; }

# 数据模型被 JSON 序列化/反序列化反射使用
-keep class top.southfish.engrave.domain.model.** { *; }
-keep class top.southfish.engrave.data.**.*Entity* { *; }

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- 离线整句翻译（只有 withTrans 会命中这些依赖，noTrans 下规则空转）----

# ONNX Runtime：张量/会话全部经 JNI 绑定，类名与方法名参与原生侧查找
-keep class ai.onnxruntime.** { *; }

# DJL 分词器：原生库经 System.loadLibrary("djl_tokenizer") 加载（Android 分支，
# 不走 JNA），JNI 按类名绑定；ai.djl.util 下的工具类被其反射调用
-keep class ai.djl.huggingface.** { *; }
-keep class ai.djl.util.** { *; }

# ML Kit 的 AAR 自带 consumer 规则；这里只消掉对未引入实现的缺类告警
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn org.slf4j.**
-dontwarn com.sun.jna.**
-dontwarn ai.djl.**
# commons-compress（DJL api 的传递依赖）在 SDK<24 分支上引用 commons-lang3，
# 我们不用那条分支，纯属可选缺类，消掉告警即可
-dontwarn org.apache.commons.lang3.**
