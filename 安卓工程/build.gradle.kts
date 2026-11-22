plugins {
    // AGP 9 自带 Kotlin 支持，不再需要 org.jetbrains.kotlin.android
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}