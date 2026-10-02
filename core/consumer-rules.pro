# :core 消费方规则：随 :app / :app-phone 的 R8 生效
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Room：实体/DAO/数据库类在运行时经反射访问
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.TypeConverter { *; }

# 数据模型被 JSON 序列化/反序列化反射使用
-keep class top.southfish.engrave.domain.model.** { *; }
-keep class top.southfish.engrave.data.**.*Entity* { *; }
