# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Compose
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Koin
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# Keep DataStore
-keep class androidx.datastore.** { *; }

# Keep WorkManager
-keep class androidx.work.** { *; }

# Keep Sealed classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep serialization
-keep class ** {
    public static **[] $VALUES;
}

# Keep Apache Commons
-keep class org.apache.commons.** { *; }
-keep class org.apache.http.** { *; }
-dontwarn org.apache.commons.**
-dontwarn org.apache.http.**
-dontwarn org.ietf.jgss.**

# Keep Skiko AWT dependencies
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn java.lang.ProcessHandle
-dontwarn javax.accessibility.Accessible
-dontwarn javax.accessibility.AccessibleContext
-keep class java.awt.** { *; }
-keep class javax.swing.** { *; }
-keep class java.lang.ProcessHandle$* { *; }
-keep class org.jetbrains.skiko.** { *; }
