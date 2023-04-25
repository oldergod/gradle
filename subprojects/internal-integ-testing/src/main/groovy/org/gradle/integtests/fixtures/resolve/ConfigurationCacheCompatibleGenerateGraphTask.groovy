/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.integtests.fixtures.resolve

import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class ConfigurationCacheCompatibleGenerateGraphTask extends AbstractGenerateGraphTask {
    @Internal
    abstract Property<ResolvedComponentResult> getRootComponent()

    @Internal
    abstract ConfigurableFileCollection getFiles()

    @Internal
    ArtifactCollection artifacts

    @Internal
    ResolvableDependencies incoming

    @TaskAction
    def generateOutput() {
        outputFile.parentFile.mkdirs()
        outputFile.withPrintWriter { writer ->
            def root = rootComponent.get()

            def components = new LinkedHashSet()
            def dependencies = new LinkedHashSet()
            collectAllComponentsAndEdges(root, components, dependencies)

            writeRootAndComponentsAndDependencies(writer, root, components, dependencies)

            if (buildArtifacts) {
                files.each {
                    writer.println("file:${it.name}")
                }
                artifacts.artifacts.each {
                    writer.println("file-artifact-incoming:${it.file.name}")
                }
            }

            artifacts.artifacts.each {
                writer.println("artifact-incoming:${it.id}")
            }


            incoming.artifactView { true }.files.each {
                writer.println("artifact-view-files:${it.name}")
            }
            incoming.artifactView { true }.artifacts.each {
                writer.println("artifact-view-artifacts:${it.file.name}")
            }
            incoming.artifactView { true }.files.files.each {
                writer.println("artifact-view-files-set:${it.name}")
            }
            incoming.artifactView { true }.artifacts.artifacts.each {
                writer.println("artifact:[${it.id.name.name}][${it.id.name.module}:${it.id.name.classifier}:${it.id.name.extension}:${it.id.name.type}]")
            }
            incoming.artifactView { it.lenient = true }.files.each {
                writer.println("artifact-view-lenient-files:${it.name}")
            }
            incoming.artifactView { it.lenient = true }.artifacts.each {
                writer.println("artifact:[${it.id.name.name}][${it.id.name.module}:${it.id.name.classifier}:${it.id.name.extension}:${it.id.name.type}]")
            }
            incoming.artifactView { it.lenient = true }.files.files.each {
                writer.println("artifact-view-lenient-files-set:${it.name}")
            }
            incoming.artifactView { true }.artifacts.artifacts.each {
                writer.println("artifact:[${it.id.name.name}][${it.id.name.module}:${it.id.name.classifier}:${it.id.name.extension}:${it.id.name.type}]")
            }
        }
    }

    static void collectAllComponentsAndEdges(ResolvedComponentResult root, Collection<ResolvedComponentResult> components, Collection<DependencyResult> dependencies) {
        def queue = [root]
        def seen = new HashSet()

        while (!queue.isEmpty()) {
            def node = queue.remove(0)
            if (seen.add(node)) {
                components.add(node)
                for (final def dep in node.getDependencies()) {
                    dependencies.add(dep)
                    if (dep instanceof ResolvedDependencyResult) {
                        queue.add(dep.selected)
                    }
                }
            } // else, already seen
        }
    }
}
