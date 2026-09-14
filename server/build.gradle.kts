import java.net.URLClassLoader
import java.util.jar.JarFile

plugins {
    id("java-conventions")
    alias(libs.plugins.shadow)
    application
}

dependencies {
    implementation(project(":portal"))
    implementation(project(":gate"))
    implementation(project(":lobby"))
    implementation(project(":battle"))
    implementation(project(":global"))
    implementation(project(":insight"))
    implementation(libs.kotlinx.cli)

    configurations.configureEach {
        exclude(group = "commons-logging", module = "commons-logging")
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
}

application {
    mainClass.set("com.jacey.game.server.ApplicationKt")
}

tasks.shadowJar {
    archiveBaseName.set("server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.jacey.game.server.ApplicationKt"
    }
    // 合并服务文件（含 META-INF/services）
    mergeServiceFiles()
    // Log4j2Plugins.dat 多 jar 合并不可靠：全部排除后 log4j2 启动时自动回退
    // 注解扫描（PluginRegistry 的 fallback），插件定位 100% 正确
    exclude("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat")
    // 依赖里原始的 reference.conf 全部排除（丢弃 shadow append 的坏合并），
    // 只用 mergeReferenceConf 任务生成的正确合并文件
    exclude("reference.conf")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}


// ============ Typesafe Config reference.conf 构建期正确合并 ============
// 用 typesafe config 官方语义：ConfigFactory.load(classLoader) 会枚举 classpath 上
// 所有 reference.conf 并深度合并 + resolve，渲染为单一 HOCON 文件供 shadowJar 打包。
tasks.register("mergeReferenceConf") {
    val outDir = layout.buildDirectory.dir("merged-conf")
    outputs.dir(outDir)
    outputs.upToDateWhen { false } // 每次重新生成（依赖 jar 集合变化不一定被感知）
    doLast {
        val out = outDir.get().asFile.resolve("akka-merged.conf")
        val files = mutableListOf<File>()
        configurations.getByName("runtimeClasspath").forEach { files += it }
        val cl = URLClassLoader(files.map { it.toURI().toURL() }.toTypedArray(), null as ClassLoader?)
        val factoryClass = Class.forName("com.typesafe.config.ConfigFactory", true, cl)
        val load = factoryClass.getMethod("load", ClassLoader::class.java)
        val config = load.invoke(null, cl)
        if (config == null) throw RuntimeException("ConfigFactory.load returned null for classloader with ${'$'}{files.size} urls")
        val crOptsClass = Class.forName("com.typesafe.config.ConfigRenderOptions", true, cl)
        val defaults = crOptsClass.getMethod("defaults").invoke(null)
        val setJson = crOptsClass.getMethod("setJson", Boolean::class.javaPrimitiveType)
        val crOpts = setJson.invoke(defaults, false)
        crOptsClass.getMethod("setOriginComments", Boolean::class.javaPrimitiveType).invoke(crOpts, false)
        val rootM = Class.forName("com.typesafe.config.Config", true, cl).getMethod("root")
        val root = rootM.invoke(config)
        val renderM = Class.forName("com.typesafe.config.ConfigObject", true, cl).getMethod("render")
        val rendered = renderM.invoke(root) as String
        out.parentFile.mkdirs()
        out.writeText(rendered)
    }
}
tasks.shadowJar {
    dependsOn("mergeReferenceConf")
    from(layout.buildDirectory.dir("merged-conf")) {
        include("akka-merged.conf")
    }
}
