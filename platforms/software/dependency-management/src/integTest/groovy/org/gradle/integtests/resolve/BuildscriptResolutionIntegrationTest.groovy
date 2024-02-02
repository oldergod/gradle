/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.dsl.GradleDsl

/**
 * Tests edge cases of buildscript configuration resolution.
 *
 * <p>Tests should cover cases of initscript, settings, standalone, and project buildscript configurations.</p>
 */
class BuildscriptResolutionIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        settingsFile << """
            rootProject.name = 'root'
        """
    }

    // This is not desired behavior. A project dependency should refer to the actual
    // project, not its buildscript component. This is a bug.
    def "project buildscript configuration can select itself"() {
        buildFile << """
            buildscript {
                configurations {
                    conf {
                        outgoing {
                            artifact file('foo.txt')
                        }
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                        }
                    }
                }

                dependencies {
                    conf project(":")
                }
            }

            task resolve {
                def files = buildscript.configurations.conf.incoming.files
                doLast {
                    assert files.files*.name == ["foo.txt"]
                }
            }
        """

        expect:
        executer.expectDocumentedDeprecationWarning("While resolving configuration 'conf', it was also selected as a variant. Configurations should not act as both a resolution root and a variant simultaneously. Depending on the resolved configuration in this manner has been deprecated. This will fail with an error in Gradle 9.0. Be sure to mark configurations meant for resolution as canBeConsumed=false or use the 'resolvable(String)' configuration factory method to create them. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#depending_on_root_configuration")
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("resolve")
    }

    // This is not desired behavior. A project dependency should refer to the actual
    // project, not its buildscript component. This is a bug.
    def "project buildscript classpath configuration can select itself"() {
        buildFile << """
            buildscript {
                configurations {
                    classpath {
                        outgoing {
                            artifact file('foo.txt')
                        }
                    }
                }

                dependencies {
                    classpath project(":")
                }
            }

            task resolve {
                def files = buildscript.configurations.classpath.incoming.files
                doLast {
                    assert files.files*.name == ["foo.txt"]
                }
            }
        """

        expect:
        2.times {
            // Once when resolving the classpath normally, once when re-resolving in `resolve`
            executer.expectDocumentedDeprecationWarning("The classpath configuration has been deprecated for consumption. This will fail with an error in Gradle 9.0. For more information, please refer to https://docs.gradle.org/current/userguide/declaring_dependencies.html#sec:deprecated-configurations in the Gradle documentation.")
            executer.expectDocumentedDeprecationWarning("While resolving configuration 'classpath', it was also selected as a variant. Configurations should not act as both a resolution root and a variant simultaneously. Depending on the resolved configuration in this manner has been deprecated. This will fail with an error in Gradle 9.0. Be sure to mark configurations meant for resolution as canBeConsumed=false or use the 'resolvable(String)' configuration factory method to create them. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#depending_on_root_configuration")
        }
        succeeds("resolve")
    }

    def "project buildscript configuration can select another project"() {
        buildFile << """
            buildscript {
                configurations {
                    conf {
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                        }
                    }
                }

                dependencies {
                    conf project(":other")
                }
            }

            task resolve {
                def files = buildscript.configurations.conf.incoming.files
                doLast {
                    assert files.files*.name == ["bar.txt"]
                }
            }
        """
        settingsFile << """
            include "other"
        """
        file("other/build.gradle") << """
            configurations {
                consumable("conf") {
                    outgoing {
                        artifact file('bar.txt')
                    }
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                    }
                }
            }
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds(":resolve")
    }

    def "project buildscript classpath configuration can select another project"() {
        settingsFile << """
            include "first"
            include "other"
        """
        file("first/build.gradle") << """
            buildscript {
                dependencies {
                    classpath project(":other")
                }
            }
        """
        file("other/build.gradle") << """
            configurations {
                other {
                    outgoing {
                        artifact file('bar.txt')
                    }
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "library"))
                    }
                }
            }
        """

        expect:
        succeeds("help")
    }

    def "project buildscript classpath configuration cannot select another project when the selected artifact is built by a task"() {
        settingsFile << """
            include "first"
            include "other"
        """
        file("first/build.gradle") << """
            buildscript {
                dependencies {
                    classpath project(":other")
                }
            }
        """
        file("other/build.gradle") << """
            task myTask { }
            configurations {
                other {
                    outgoing {
                        artifact(file('bar.txt')) {
                            builtBy tasks.myTask
                        }
                    }
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "library"))
                    }
                }
            }
        """

        expect:
        fails("help")
        failure.assertHasCause("Script classpath dependencies must reside in a separate build from the script itself.")
    }

    // This is not desired behavior. A project dependency should refer to the actual
    // project, not its buildscript component. This is a bug.
    def "project buildscript configuration can select other buildscript configurations"() {
        buildFile << """
            buildscript {
                configurations {
                    conf {
                        canBeConsumed = false
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "other"))
                        }
                    }
                    other {
                        outgoing {
                            artifact file('bar.txt')
                        }
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "other"))
                        }
                    }
                }

                dependencies {
                    conf project(":")
                }
            }

            task resolve {
                def files = buildscript.configurations.conf.incoming.files
                doLast {
                    assert files*.name == ["bar.txt"]
                }
            }
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("resolve")
    }

    // This is not desired behavior. A project dependency should refer to the actual
    // project, not its buildscript component. This is a bug.
    def "project buildscript classpath configuration can select other buildscript configurations"() {
        buildFile << """
            buildscript {
                configurations {
                    classpath {
                        // To ensure it doesn't select itself over `other`
                        canBeConsumed = false
                    }
                    other {
                        outgoing {
                            artifact file('bar.txt')
                        }
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "library"))
                        }
                    }
                }

                dependencies {
                    classpath project(":")
                }
            }

            task resolve {
                def files = buildscript.configurations.classpath.incoming.files
                doLast {
                    assert files*.name == ["bar.txt"]
                }
            }
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Allowed usage is changing for configuration ':classpath', consumable was true and is now false. Ideally, usage should be fixed upon creation. This behavior has been deprecated. This behavior is scheduled to be removed in Gradle 9.0. Usage should be fixed upon creation. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#configurations_allowed_usage")
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("resolve")
    }

    // This is not desired behavior. A project dependency should refer to the actual
    // project, not its buildscript component. This is a bug.
    def "project buildscript resolvable configuration and consumable configuration from same project live in same resolved component"() {
        buildFile << """
            buildscript {
                configurations {
                    conf {
                        canBeConsumed = false
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                        }
                    }
                    other {
                        outgoing {
                            artifact file("foo.txt")
                        }
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                        }
                    }
                }

                dependencies {
                    conf project(":")
                }
            }

            task resolve {
                def rootComponent = buildscript.configurations.conf.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootComponent.get()
                    assert root.id.projectName == 'root'
                    assert root.variants.size() == 2
                    def conf = root.variants.find { it.displayName == 'conf' }
                    def other = root.variants.find { it.displayName == 'other' }
                    assert conf != null
                    assert other != null
                }
            }
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("resolve")
    }

    // This is not necessarily desired behavior, or important behavior at all -- who cares about the identity of the buildscript classpath resolution?
    // The buildscript is _not_ the project. It should not claim to be the project.
    // Ideally, this configuration would have an unspecified identity, similar to init, settings, and standalone scripts.
    def "project buildscript classpath configuration is identified by the root project's identity"() {
        buildFile << """
            version = "1.0"
            group = "foo"

            task resolve {
                def rootComponent = buildscript.configurations.classpath.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootComponent.get()
                    assert root.moduleVersion.group == "foo"
                    assert root.moduleVersion.name == "root"
                    assert root.moduleVersion.version == "1.0"
                    assert root.id instanceof ProjectComponentIdentifier
                    assert root.id.projectName == "root"
                    assert root.id.build.buildPath == ":"
                    assert root.id.projectPath == ":"
                    assert root.id.buildTreePath == ":"
                    assert root.id.projectName == "root"
                }
            }
        """

        expect:
        succeeds("resolve")
    }

    def "settings buildscript classpath configuration has unspecified identity"() {
        settingsFile << """
            def rootComponent = buildscript.configurations.classpath.incoming.resolutionResult.rootComponent
            def root = rootComponent.get()
            assert root.moduleVersion.group == "unspecified"
            assert root.moduleVersion.name == "unspecified"
            assert root.moduleVersion.version == "unspecified"
            assert root.id instanceof ModuleComponentIdentifier
            assert root.id.module == "unspecified"
            assert root.id.group == "unspecified"
            assert root.id.version == "unspecified"
        """

        expect:
        succeeds("help")
    }

    def "init buildscript classpath configuration has unspecified identity"() {
        initScriptFile << """
            def rootComponent = buildscript.configurations.classpath.incoming.resolutionResult.rootComponent
            def root = rootComponent.get()
            assert root.moduleVersion.group == "unspecified"
            assert root.moduleVersion.name == "unspecified"
            assert root.moduleVersion.version == "unspecified"
            assert root.id instanceof ModuleComponentIdentifier
            assert root.id.module == "unspecified"
            assert root.id.group == "unspecified"
            assert root.id.version == "unspecified"
        """

        when:
        executer.usingInitScript(initScriptFile)

        then:
        succeeds("help")
    }

    def "standalone buildscript classpath configuration has unspecified identity"() {
        file("foo.gradle") << """
            def rootComponent = buildscript.configurations.classpath.incoming.resolutionResult.rootComponent
            def root = rootComponent.get()
            assert root.moduleVersion.group == "unspecified"
            assert root.moduleVersion.name == "unspecified"
            assert root.moduleVersion.version == "unspecified"
            assert root.id instanceof ModuleComponentIdentifier
            assert root.id.module == "unspecified"
            assert root.id.group == "unspecified"
            assert root.id.version == "unspecified"
        """

        buildFile << """
            apply from: "foo.gradle"
        """

        expect:
        succeeds("help")
    }

    def "Adding configuration to project buildscript is deprecated"() {
        buildFile << """
            buildscript {
                configurations {
                    newconf
                }
            }
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for buildscript of root project 'root' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("help")
    }

    def "Adding configuration to settings buildscript is deprecated"() {
        settingsFile << """
            buildscript {
                configurations {
                    newconf
                }
            }
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for settings file 'settings.gradle' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("help")
    }

    def "Adding configuration to init buildscript is deprecated"() {
        initScriptFile << """
            buildscript {
                configurations {
                    newconf
                }
            }
        """

        when:
        executer.usingInitScript(initScriptFile)

        then:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for initialization script 'init.gradle' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("help")
    }

    def "Adding configuration to standalone buildscript is deprecated"() {
        file("foo.gradle") << """
            buildscript {
                configurations {
                    newconf
                }
            }
        """

        buildFile << """
            apply from: "foo.gradle"
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for script 'foo.gradle' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("help")
    }

    def "project buildscripts support detached configurations for resolving external dependencies"() {
        mavenRepo.module("org", "foo").publish()
        buildFile << """
            buildscript {
                ${mavenTestRepository()}
            }
            task resolve {
                def files = buildscript.configurations.detachedConfiguration(
                    buildscript.dependencies.create("org:foo:1.0")
                ).incoming.files
                doLast {
                    assert files.files*.name == ["foo-1.0.jar"]
                }
            }
        """

        expect:
        succeeds("resolve")
    }

    def "settings buildscripts support detached configurations for resolving external dependencies"() {
        mavenRepo.module("org", "foo").publish()
        settingsFile << """
            buildscript {
                ${mavenTestRepository()}
            }
            def files = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create("org:foo:1.0")
            ).incoming.files
            assert files.files*.name == ["foo-1.0.jar"]
        """

        expect:
        succeeds("help")
    }

    def "init buildscripts support detached configurations for resolving external dependencies"() {
        mavenRepo.module("org", "foo").publish()
        initScriptFile << """
            buildscript {
                ${mavenTestRepository()}
            }
            def files = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create("org:foo:1.0")
            ).incoming.files
            assert files.files*.name == ["foo-1.0.jar"]
        """

        when:
        executer.usingInitScript(initScriptFile)

        then:
        succeeds("help")
    }

    def "standalone buildscripts support detached configurations for resolving external dependencies"() {
        mavenRepo.module("org", "foo").publish()
        file("foo.gradle") << """
            buildscript {
                ${mavenTestRepository()}
            }
            def files = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create("org:foo:1.0")
            ).incoming.files
            assert files.files*.name == ["foo-1.0.jar"]
        """

        buildFile << """
            apply from: "foo.gradle"
        """

        expect:
        succeeds("help")
    }

    // This is not necessarily desired behavior, or important behavior at all.
    // The detached configuration is _not_ the project. It should not claim to be the project.
    // Ideally, this configuration would have an unspecified identity, similar to init, settings, and standalone scripts.
    def "project buildscripts detached configurations are identified by the root project's identity"() {
        mavenRepo.module("org", "foo").publish()
        buildFile << """
            buildscript {
                ${mavenTestRepository()}
            }

            version = "1.0"
            group = "foo"

            task resolve {
                def rootComponent = buildscript.configurations.detachedConfiguration(
                    buildscript.dependencies.create("org:foo:1.0")
                ).incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootComponent.get()
                    assert root.moduleVersion.group == "foo"
                    assert root.moduleVersion.name == "root"
                    assert root.moduleVersion.version == "1.0"
                    assert root.id instanceof ProjectComponentIdentifier
                    assert root.id.projectName == "root"
                    assert root.id.build.buildPath == ":"
                    assert root.id.projectPath == ":"
                    assert root.id.buildTreePath == ":"
                    assert root.id.projectName == "root"
                }
            }
        """

        expect:
        succeeds("resolve")
    }

    def "settings buildscripts support detached configurations have unspecified identity"() {
        mavenRepo.module("org", "foo").publish()
        settingsFile << """
            buildscript {
                ${mavenTestRepository()}
            }
            def rootComponent = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create("org:foo:1.0")
            ).incoming.resolutionResult.rootComponent
            def root = rootComponent.get()
            assert root.moduleVersion.group == "unspecified"
            assert root.moduleVersion.name == "unspecified"
            assert root.moduleVersion.version == "unspecified"
            assert root.id instanceof ModuleComponentIdentifier
            assert root.id.module == "unspecified"
            assert root.id.group == "unspecified"
            assert root.id.version == "unspecified"
        """

        expect:
        succeeds("help")
    }

    def "init buildscripts support detached configurations have unspecified identity"() {
        mavenRepo.module("org", "foo").publish()
        initScriptFile << """
            buildscript {
                ${mavenTestRepository()}
            }
            def rootComponent = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create("org:foo:1.0")
            ).incoming.resolutionResult.rootComponent
            def root = rootComponent.get()
            assert root.moduleVersion.group == "unspecified"
            assert root.moduleVersion.name == "unspecified"
            assert root.moduleVersion.version == "unspecified"
            assert root.id instanceof ModuleComponentIdentifier
            assert root.id.module == "unspecified"
            assert root.id.group == "unspecified"
            assert root.id.version == "unspecified"
        """

        when:
        executer.usingInitScript(initScriptFile)

        then:
        succeeds("help")
    }

    def "standalone buildscripts support detached configurations have unspecified identity"() {
        mavenRepo.module("org", "foo").publish()
        file("foo.gradle") << """
            buildscript {
                ${mavenTestRepository()}
            }
            def rootComponent = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create("org:foo:1.0")
            ).incoming.resolutionResult.rootComponent
            def root = rootComponent.get()
            assert root.moduleVersion.group == "unspecified"
            assert root.moduleVersion.name == "unspecified"
            assert root.moduleVersion.version == "unspecified"
            assert root.id instanceof ModuleComponentIdentifier
            assert root.id.module == "unspecified"
            assert root.id.group == "unspecified"
            assert root.id.version == "unspecified"
        """

        buildFile << """
            apply from: "foo.gradle"
        """

        expect:
        succeeds("help")
    }

    def "project buildscripts support detached configurations for resolving local dependencies"() {
        buildFile << """
            task resolve {
                def conf = buildscript.configurations.detachedConfiguration(
                    buildscript.dependencies.create(project(":other"))
                )
                conf.attributes {
                    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                }
                def files = conf.incoming.files
                doLast {
                    assert files.files*.name == ["foo.txt"]
                }
            }
        """
        settingsFile << """
            include "other"
        """
        file("other/build.gradle") << """
            configurations {
                consumable("foo") {
                    outgoing {
                        artifact file("foo.txt")
                    }
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                    }
                }
            }
        """

        expect:
        succeeds(":resolve")
    }

    def "standalone buildscripts support detached configurations for resolving local dependencies"() {
        mavenRepo.module("org", "foo").publish()
        file("foo.gradle") << """
            buildscript {
                ${mavenTestRepository()}
            }
            def files = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create(project(":other"))
            ).incoming.files
            assert files.files*.name == ["foo.txt"]
        """
        settingsFile << """
            include "other"
        """
        file("other/build.gradle") << """
            configurations {
                consumable("foo") {
                    outgoing {
                        artifact file("foo.txt")
                    }
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "foo"))
                    }
                }
            }
        """

        buildFile << """
            apply from: "foo.gradle"
        """

        expect:
        succeeds("help")
    }

    def "creating a settings buildscript configuration is deprecated in Kotlin"() {
        mavenRepo.module("org", "foo").publish()
        settingsFile.delete()
        settingsKotlinFile << """
            buildscript {
                ${mavenTestRepository(GradleDsl.KOTLIN)}
                configurations {
                    create("myConfig")
                }
                dependencies {
                    "myConfig"("org:foo:1.0")
                }
            }

            val files = buildscript.configurations["myConfig"].files
            assert(files.map { it.name } == listOf("foo-1.0.jar"))
        """

        expect:
        executer.expectDocumentedDeprecationWarning("Mutating configuration container for settings file 'settings.gradle.kts' has been deprecated. This will fail with an error in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#creating_new_buildscript_configurations")
        succeeds("help")
    }

    def "creating a detached settings buildscript configuration works in Kotlin"() {
        mavenRepo.module("org", "foo").publish()
        settingsFile.delete()
        settingsKotlinFile << """
            buildscript {
                ${mavenTestRepository(GradleDsl.KOTLIN)}
            }

            val myConfig = buildscript.configurations.detachedConfiguration(
                buildscript.dependencies.create("org:foo:1.0")
            )

            val files = myConfig.files
            assert(files.map { it.name } == listOf("foo-1.0.jar"))
        """

        expect:
        succeeds("help")
    }
}
