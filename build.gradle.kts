val realmBaseName: String by project
val realmSyncName: String by project
val realmVersion: String by project
val coroutinesCoreName: String by project
val coroutinesRx3Name: String by project
val kotlinxVersion: String by project
val rxJavaName: String by project
val rxjavaVersion: String by project
val rxKotlinVersion: String by project
val rxKotlinName: String by project

plugins {
    kotlin("jvm") version "1.9.20"
}

group = "dev.specter"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {

    implementation("$realmBaseName:$realmVersion")
    implementation("$realmSyncName:$realmVersion")
    implementation("$coroutinesCoreName:$kotlinxVersion")
    implementation("$coroutinesRx3Name:$kotlinxVersion")
    implementation("$rxKotlinName:$rxKotlinVersion")
    implementation("$rxJavaName:$rxjavaVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}