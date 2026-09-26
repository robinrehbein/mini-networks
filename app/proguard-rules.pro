# R8 rules for release builds (minify + resource shrinking), on top of proguard-android-optimize.txt.
# The libraries ship their own consumer rules (kotlinx.serialization, Mobile Ads, UMP, Play Billing); the rules below
# cover what they cannot know about this app, plus a safety net for the paths only a real device exercises.

# Readable crash reports in the Play Console (upload build/outputs/mapping/release/mapping.txt with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: save games (WorldSnapshot and its parts in :core). The generated serializers are reached via
# the companion's serializer(); keep them, the companions and the $$serializer classes so saves written by one release
# still decode in the next, whatever R8 decides to inline or rename.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.mininetworks.game.** {
    static ** Companion;
    static **$$serializer INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
    *** Companion;
}
-keepclasseswithmembers class com.mininetworks.game.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mininetworks.game.**$$serializer { *; }
-keepclassmembers enum com.mininetworks.game.game.** {
    **[] $VALUES;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Google Mobile Ads, UMP and Play Billing need nothing extra: their AARs ship consumer rules (the ad activity, the
# adapters and the Play Store AIDL interface are kept there). Whole-package keeps here would only stop R8 from
# shrinking the SDKs. The one rule below guards the Billing AIDL stub, which the Play Store binds to by name.
-keep class com.android.vending.billing.** { *; }
