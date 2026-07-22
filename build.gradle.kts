plugins {
    application
}

group = "ru.rainedev"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform("it.tdlight:tdlight-java-bom:3.5.3+td.1.8.65"))
    implementation("it.tdlight:tdlight-java")
    // Нативы под конкретную платформу. gnu_ssl3 — glibc + OpenSSL 3.x.
    // Для другой архитектуры меняется только classifier.
    implementation("it.tdlight:tdlight-natives:4.0.589:linux_amd64_gnu_ssl3")

    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    // чистый Java-порт libopus: голосовые кодируются без внешних программ
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // чистый Java-декодер видео: кадры извлекаются без внешних программ
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jcodec:jcodec-javase:0.2.5")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "ru.rainedev.raine.Main"
}

// Промпты едут внутри сборки: если рядом с ботом их не окажется,
// он выложит их из себя и запустится, а не упадёт на первом же файле.
tasks.processResources {
    from("prompts") { into("prompts") }
}

tasks.test {
    useJUnitPlatform {
        // тесты, которые ходят в сеть и тратят токены, по умолчанию не гоняем
        excludeTags("integration")
    }
    // TDLib грузится через System.loadLibrary — с JDK 24+ это требует явного разрешения
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<Test>("integrationTest") {
    description = "Тесты, обращающиеся к настоящему LLM-эндпоинту."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    systemProperty("raine.diary", providers.systemProperty("raine.diary").getOrElse(""))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    outputs.upToDateWhen { false }
}

tasks.named<JavaExec>("run") {
    // авторизация в TDLib идёт через ввод кода из Telegram в консоль
    standardInput = System.`in`
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // -Praine.tdlib.verbosity=3 — когда надо увидеть, что приходит с сервера
    systemProperty("raine.tdlib.verbosity",
            providers.gradleProperty("raine.tdlib.verbosity").getOrElse("1"))
}

tasks.named<CreateStartScripts>("startScripts") {
    defaultJvmOpts = listOf("--enable-native-access=ALL-UNNAMED")
}
