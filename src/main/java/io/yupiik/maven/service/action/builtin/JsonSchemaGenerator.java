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
package io.yupiik.maven.service.action.builtin;

import io.yupiik.maven.service.json.JsonSchema2Adoc;
import lombok.RequiredArgsConstructor;
import org.apache.johnzon.jsonschema.generator.Schema;
import org.apache.johnzon.jsonschema.generator.SchemaProcessor;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.config.PropertyOrderStrategy;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

@RequiredArgsConstructor
public class JsonSchemaGenerator implements Runnable {
    private final Map<String, String> configuration;

    @Override
    public void run() {
        final Class<?> clazz;
        try {
            clazz = Thread.currentThread().getContextClassLoader()
                    .loadClass(requireNonNull(configuration.get("class"), "No class attribute set on " + getClass().getSimpleName()));
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
        final Path output = Paths.get(requireNonNull(configuration.get("to"), "No to attribute set on " + getClass().getSimpleName()));
        final String type = ofNullable(configuration.get("type")).map(it -> it.trim().toUpperCase(ROOT)).orElse("JSON");

        final SchemaProcessor processor = new SchemaProcessor(
                ofNullable(configuration.get("setClassAsTitle")).map(Boolean::parseBoolean).orElse(false),
                ofNullable(configuration.get("useReflectionForDefaults")).map(Boolean::parseBoolean).orElse(false)) {
            @Override
            protected void fillMeta(final Field f, final Schema schema) {
                super.fillMeta(f, schema);
                if (schema.getTitle() == null) {
                    schema.setTitle(f.getDeclaringClass() + "." + f.getName());
                }
                if (schema.getDescription() == null) {
                    schema.setDescription(schema.getTitle());
                }
            }
        };
        final SchemaProcessor.InMemoryCache cache = new SchemaProcessor.InMemoryCache();
        final Schema schema = processor.mapSchemaFromClass(clazz, cache);
        schema.setTitle(configuration.get("title"));
        schema.setDescription(configuration.get("description"));
        schema.setDefinitions(cache.getDefinitions());

        String content;
        switch (type) {
            case "ADOC":
                content = toAdoc(schema, ofNullable(configuration.get("levelPrefix")).orElse("="));
                break;
            case "JSON":
                content = toJson(schema, ofNullable(configuration.get("pretty")).map(Boolean::parseBoolean).orElse(true));
                break;
            default:
                throw new IllegalArgumentException("Unknown argument: '" + type + "' as type.");
        }

        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.write(output, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException iore) {
            throw new IllegalStateException(iore);
        }
    }

    private String toJson(final Schema schema, final boolean pretty) {
        try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig()
                .setProperty("johnzon.skip-cdi", true)
                .withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL)
                .withFormatting(pretty))) {
            return jsonb.toJson(schema);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toAdoc(final Schema schema, final String levelPrefix) {
        final JsonSchema2Adoc schema2Adoc = new JsonSchema2Adoc(levelPrefix, schema, s -> s.getRef() != null) {
            @Override
            public void prepare(final Schema in) {
                if (in == null) {
                    super.prepare(in);
                    return;
                }
                if (in.getTitle() == null) {
                    in.setTitle("Model");
                }
                if (in.getDescription() == null) {
                    in.setDescription("");
                }
                super.prepare(in);
            }
        };
        schema2Adoc.prepare(null);
        return schema2Adoc.get().toString();
    }
}
