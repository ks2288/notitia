# Usage

This library is able to be used as a JVM library in two ways: through
[Jitpack](https://jitpack.io) as a traditional external library or, if you
don't want to/can't modify your build script for whatever reason, as a submodule
within your own Git repo.

### Gradle

#### Groovy
In your `settings.gradle`, add to your existing closure OR define a new
dependency management resolution closure to match the following:
```
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' } // this is the new maven addition
    }
}
```

***NOTE: the `dependencyResolutionManagement` closure is not scoped the main
`build.gradle` file, and it will not be available there under otherwise
"normal" circumstances while a `settings.gradle` file is present***

Then, add the following to your `dependencies` closure within your
`build.gradle` file:
```
implementation 'com.github.ks2288:notitia:1.0.0-SNAPSHOT'
```

#### Kotlin
The syntax for Kotlin DSL is mostly identical, with the only difference
existing in the format of the Maven repo definition function that matches Kotlin
styling (`URL` function parameter) as opposed to that seen with Groovy (`URL`
built inside closure with Groovy-style assignment syntax). Within your
`settings.gradle.kts` file, match the existing repo definitions with the
following resolution closure:
```
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://jitpack.io") // new maven addition; note the difference
    }
}
```
Then, add the following to your existing `dependencies` closure within your
`build.gradle.kts` file:
```
 implementation("com.github.ks2288:notitia:1.0.0-SNAPSHOT")
```

#### Maven

To use this library within a Maven-based project, add the following to your
Maven build file
```
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Then add the dependency in the same manner:

```
<dependency>
    <groupId>com.github.ks2288</groupId>
    <artifactId>notitia</artifactId>
    <version>Tag</version>
</dependency>
```

### Submodule (`Git`)
To use this code directly within your own project and gain the ability
to edit/add to it as you please (or just use it as a base of your own to add
to a different upstream at some point), simply execute the following `git`
command from your machine's CLI within the root directory of the local repo:
```
git submodule add https://github.com/ks2288/notitia.git
```