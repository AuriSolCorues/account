# Kotlin Serialization 通过生成的序列化器访问这些模型。
# 保留序列化器入口，同时允许 R8 移除 Release 中未使用的 Compose/调试代码。
-keepclassmembers class com.example.account.**$$serializer {
    public static ** INSTANCE;
}
