rootProject.name = "buildSrc"

// buildSrc 复用主工程的版本目录（gradle/libs.versions.toml）
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
