# Отладочные логи (v/d/i) вырезаются из release; предупреждения (w) остаются
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Anthropic SDK тянет opentelemetry-api, которому нужна аннотация AutoValue только при компиляции
-dontwarn com.google.auto.value.AutoValue
