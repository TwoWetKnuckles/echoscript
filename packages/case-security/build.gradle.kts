plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    // Android supplies tink-android; the independent JVM tool supplies tink.
    // Keeping these compile-only avoids duplicate crypto classes in the APK.
    compileOnly("com.google.crypto.tink:tink:1.24.0")
    compileOnly("io.github.erdtman:java-json-canonicalization:1.1")
    compileOnly("org.json:json:20260814")
    testImplementation("com.google.crypto.tink:tink:1.24.0")
    testImplementation("io.github.erdtman:java-json-canonicalization:1.1")
    testImplementation("org.json:json:20260814")
    testImplementation("junit:junit:4.13.2")
}
