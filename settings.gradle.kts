rootProject.name = "raine"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // TDLight не публикуется в Maven Central — только в собственном репозитории
        maven("https://mvn.mchv.eu/repository/mchv/")
    }
}
