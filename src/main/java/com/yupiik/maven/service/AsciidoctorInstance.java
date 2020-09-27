/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.yupiik.maven.service;

import com.yupiik.maven.mojo.BaseMojo;
import com.yupiik.maven.service.extension.DependenciesMacro;
import com.yupiik.maven.service.extension.ExcelTableMacro;
import com.yupiik.maven.service.extension.JLatexMath;
import com.yupiik.maven.service.extension.XsltMacro;
import org.apache.maven.plugin.logging.Log;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.extension.JavaExtensionRegistry;
import org.asciidoctor.jruby.internal.JRubyAsciidoctor;

import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Logger;

import static java.util.Optional.ofNullable;

@Named
@Singleton
public class AsciidoctorInstance {
    // for concurrent builds
    private final Queue<Asciidoctor> instances = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<BaseMojo> mojo = new ThreadLocal<>();

    public <T> T withAsciidoc(final BaseMojo base, final Function<Asciidoctor, T> task) {
        Asciidoctor poll = instances.poll();
        if (poll == null) {
            poll = newInstance(base.getLog(), base.getWorkDir().toPath().resolve("gem"), base.getCustomGems(), base.getRequires());
        }
        mojo.set(base);
        try {
            return task.apply(poll);
        } finally {
            mojo.remove();
            instances.add(poll);
        }
    }

    private Asciidoctor newInstance(final Log log, final Path path, final String customGems, final List<String> requires) {
        final Asciidoctor asciidoctor = JRubyAsciidoctor.create(ofNullable(customGems).orElseGet(path::toString));
        Logger.getLogger("asciidoctor").setUseParentHandlers(false);
        registerExtensions(asciidoctor.javaExtensionRegistry());
        asciidoctor.registerLogHandler(logRecord -> {
            switch (logRecord.getSeverity()) {
                case UNKNOWN:
                case INFO:
                    log.info(logRecord.getMessage());
                    break;
                case ERROR:
                case FATAL:
                    log.error(logRecord.getMessage());
                    break;
                case WARN:
                    log.warn(logRecord.getMessage());
                    break;
                case DEBUG:
                default:
                    log.debug(logRecord.getMessage());
            }
        });
        if (requires != null) {
            asciidoctor.requireLibraries(requires);
        } else {
            asciidoctor.requireLibrary("asciidoctor-diagram");
            asciidoctor.requireLibrary("asciidoctor-revealjs");
            try {
                asciidoctor.requireLibrary(Files.list(path.resolve("gems"))
                        .filter(it -> it.getFileName().toString().startsWith("asciidoctor-bespoke-"))
                        .findFirst()
                        .map(it -> it.resolve("lib/asciidoctor-bespoke.rb"))
                        .orElseThrow(() -> new IllegalStateException("bespoke was not bundled at build time"))
                        .toAbsolutePath().normalize().toString());
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return asciidoctor;
    }

    private void registerExtensions(final JavaExtensionRegistry registry) {
        registry.block(new DependenciesMacro(mojo::get));
        registry.block(new XsltMacro(mojo::get));
        registry.block(new ExcelTableMacro());
        try {
            Thread.currentThread().getContextClassLoader().loadClass("org.scilab.forge.jlatexmath.TeXFormula");
            registry.inlineMacro(new JLatexMath.Inline());
            registry.block(new JLatexMath.Block());
        } catch (final ClassNotFoundException cnfe) {
            // no-op
        }
    }

    @PreDestroy
    public void destroy() {
        instances.forEach(Asciidoctor::shutdown);
    }
}
