plugins {
	kotlin("jvm") version "2.3.21"
	id("com.google.devtools.ksp") version "2.3.7"
	`maven-publish`
}

group = "kr.acda"
version = "1.0.0"

repositories {
	mavenCentral()
	mavenLocal()
	maven("https://repo.papermc.io/repository/maven-public/")
	maven("https://repo.acda.kr/repository/maven-releases/")
	maven("https://repo.acda.kr/repository/maven-snapshots/")
}

dependencies {
	testImplementation(kotlin("test"))
	compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

	compileOnly("kr.acda.arccore:arc-core:1.0.0")
	ksp("kr.acda.arccore:arc-ksp:1.0.0")
	compileOnly("kr.acda.arccore:arc-database:1.0.0")
	compileOnly("org.jetbrains.exposed:exposed-core:1.0.0-rc-4")
	compileOnly("org.jetbrains.exposed:exposed-jdbc:1.0.0-rc-4")
	compileOnly("org.jetbrains.exposed:exposed-dao:1.0.0-rc-4")
	compileOnly("kr.acda:arcplaceholder:1.0.0")
}

kotlin {
	jvmToolchain(21)
}

java {
	withSourcesJar()
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			groupId = project.group.toString()
			artifactId = project.name
			version = project.version.toString()
			from(components["java"])
			pom { name.set(project.name) }
		}
	}
	repositories {
		maven {
			name = "nexus"
			val isSnapshot = project.version.toString().endsWith("SNAPSHOT")
			url = uri(
				if (isSnapshot)
					"https://repo.acda.kr/repository/maven-snapshots/"
				else
					"https://repo.acda.kr/repository/maven-releases/"
			)
			credentials {
				username = findProperty("repoUser")?.toString() ?: System.getenv("REPO_USER")
				password = findProperty("repoPassword")?.toString() ?: System.getenv("REPO_PASSWORD")
			}
		}
	}
}

tasks.test {
	useJUnitPlatform()
}
