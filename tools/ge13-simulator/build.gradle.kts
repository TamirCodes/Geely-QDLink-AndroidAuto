plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":qdlink-core"))
}

application {
    mainClass.set("io.github.geelyqdlink.simulator.MainKt")
}

