# gomobile's generated JNI surface. R8 must not rename or strip these.
-keep class go.** { *; }
-keep class io.nekobox.next.corelib.** { *; }
-keep class io.nekobox.next.core.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod

# kotlinx.serialization. The engine module is a plain jar, so these rules live here.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class io.nekobox.next.engine.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Room. Generated code and the database class stay.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**
