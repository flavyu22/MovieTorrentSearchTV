# Retrofit and Gson inspect these attributes at runtime. Their artifacts also ship
# consumer rules; the local rules below cover this application's reflection DTOs.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

-keepclassmembers,allowoptimization class io.github.flavyu22.movietorrentsearchtv.model.** {
    <fields>;
}

-keepclassmembers,allowoptimization class io.github.flavyu22.movietorrentsearchtv.api.UpdateInfo {
    <fields>;
}

# Remove verbose diagnostics in production while retaining warning/error signals.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
