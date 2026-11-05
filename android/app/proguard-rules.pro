# Registros, DTOs e respostas da API são lidos/escritos pelo Gson por reflexão: os nomes dos campos são o formato
# do JSON (sincronizado com o PWA e o servidor), então não podem ser renomeados nem removidos.
-keepclassmembers class br.com.contaslt.** {
    <fields>;
    <init>();
}
-keep class br.com.contaslt.data.schema.** { *; }
-keep class br.com.contaslt.dto.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Gson
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
