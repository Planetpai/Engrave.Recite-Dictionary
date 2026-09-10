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
