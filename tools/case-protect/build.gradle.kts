plugins { application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("org.echoscript.cli.CaseProtect") }
dependencies {
    implementation(project(":case-security"))
    implementation("com.google.crypto.tink:tink:1.24.0")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("org.json:json:20260814")
}
