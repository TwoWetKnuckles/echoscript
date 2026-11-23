pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "echoscript-case-protect"
include(":case-security")
project(":case-security").projectDir = file("../../packages/case-security")
