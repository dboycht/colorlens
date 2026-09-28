# Keep line numbers so crash reports from the field stay actionable.
-keepattributes SourceFile,LineNumberTable

# The SVG/report generator and the color tables are pure data; R8 renaming them
# is harmless, but reflective enum lookups by name (settings persistence) are
# not, so keep enum valueOf/values intact.
-keepclassmembers enum com.dboycht.colorlens.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
