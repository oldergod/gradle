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

package org.gradle.api.internal.tasks.compile;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A service that backups a class just before new version of a class is generated.
 * This is used for restoring compiler outputs on compilation failure.
 *
 * Service only backups classes that:
 * - exists before compilation
 * - were not deleted before compilation
 * - javac tries to overwrite during compilation.
 *
 * This service is called by compiler through Compiler API.
 *
 * We don't backup classes/resources generated by annotation processors, since any overwriting of existing generated types
 * that were not deleted by Gradle before annotation processing, would anyway result in full-recompilation, due to annotation processors limitations.
 * And in case of a Groovy/Java joint compilation, incremental compilation is anyway disabled, when annotation processing is present.
 */
public class CompilationClassBackupService {

    private final Set<String> classesToCompile;
    private final File destinationDir;
    private final File headerOutputDir;
    private final File classBackupDir;
    private final ApiCompilerResult result;
    private final AtomicLong uniqueIndex;
    private final boolean shouldBackupFiles;

    public CompilationClassBackupService(JavaCompileSpec spec, ApiCompilerResult result) {
        this.classesToCompile = spec.getClassesToCompile();
        this.destinationDir = spec.getDestinationDir();
        this.headerOutputDir = spec.getCompileOptions().getHeaderOutputDirectory();
        this.classBackupDir = spec.getClassBackupDir();
        this.shouldBackupFiles = classBackupDir != null;
        this.result = result;
        this.uniqueIndex = new AtomicLong();
    }

    public void maybeBackupClassFile(String classFqName) {
        // Classes to compile are stashed before the compilation, so there is nothing to backup
        if (shouldBackupFiles) {
            String classFilePath = classFqName.replace(".", "/").concat(".class");
            maybeBackupFile(destinationDir, classFilePath);
            if (headerOutputDir != null) {
                String headerFilePath = classFqName.replaceAll("[.$]", "_").concat(".h");
                maybeBackupFile(headerOutputDir, headerFilePath);
            }
        }
    }

    private void maybeBackupFile(File destinationDir, String relativePath) {
        File classFile = new File(destinationDir, relativePath);
        if (!result.getBackupClassFiles().containsKey(classFile.getAbsolutePath()) && classFile.exists()) {
            File backupFile = new File(classBackupDir, classFile.getName() + uniqueIndex.incrementAndGet());
            copy(classFile.toPath(), backupFile.toPath());
            result.getBackupClassFiles().put(classFile.getAbsolutePath(), backupFile.getAbsolutePath());
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
