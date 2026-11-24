plugins {
    alias(libs.plugins.convention.publish)
}

dependencies {
    compileOnly(libs.bundles.minecraft)
    compileOnly(libs.concurrentutil)
    testImplementation(libs.bundles.minecraft)
    testRuntimeOnly(libs.concurrentutil)
}
