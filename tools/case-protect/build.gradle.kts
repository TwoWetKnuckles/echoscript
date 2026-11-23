import java.security.MessageDigest

plugins { application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("org.echoscript.cli.CaseProtect") }
dependencies {
    implementation(project(":case-security"))
    implementation("com.google.crypto.tink:tink:1.24.0")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("org.json:json:20260814")
}
val runtimeInventory = tasks.register("runtimeInventory") {
    val destination = layout.buildDirectory.file("reports/runtime-components.tsv")
    val ownJar = tasks.named<Jar>("jar")
    inputs.files(configurations.runtimeClasspath, ownJar)
    dependsOn(configurations.runtimeClasspath, ownJar)
    outputs.file(destination)
    doLast {
        val files = configurations.runtimeClasspath.get().files + ownJar.get().archiveFile.get().asFile
        val entries = files.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val bytes = ByteArray(65536)
                while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            "${file.name}\t$hash"
        }.sorted()
        destination.get().asFile.apply { parentFile.mkdirs(); writeText("file\tsha256\n" + entries.joinToString("\n", postfix = "\n")) }
    }
}
distributions {
    main {
        contents {
            from("../../LICENSE", "../../NOTICE", "../../DISCLAIMER.md", "THIRD_PARTY_NOTICES.txt", "VERIFY.txt")
            from("licenses") { into("licenses") }
            from(runtimeInventory)
        }
    }
}
