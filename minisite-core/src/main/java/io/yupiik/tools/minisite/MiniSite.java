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
package io.yupiik.tools.minisite;

import lombok.RequiredArgsConstructor;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.Options;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;
import org.asciidoctor.ast.DocumentHeader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

@RequiredArgsConstructor
public class MiniSite implements Runnable {
    private final MiniSiteConfiguration configuration;

    @Override
    public void run() {
        configuration.fixConfig();
        executePreActions();
        if (configuration.isSkipRendering()) {
            configuration.getAsciidoctorConfiguration().info().accept("Rendering (and upload) skipped");
            return;
        }
        final Options options = createOptions();
        configuration.getAsciidoctorPool().apply(configuration.getAsciidoctorConfiguration(), a -> {
            doRender(a, options);
            return null;
        });
    }

    public void executePreActions() {
        if (configuration.getPreActions() == null || configuration.getPreActions().isEmpty()) {
            return;
        }
        final var executor = new ActionExecutor();
        final Thread thread = Thread.currentThread();
        final ClassLoader parentLoader = thread.getContextClassLoader();
        final var classLoader = ofNullable(configuration.getActionClassLoader().get())
                .orElseGet(Thread.currentThread()::getContextClassLoader);
        try {
            thread.setContextClassLoader(classLoader);
            configuration.getPreActions().forEach(it -> executor.execute(it, configuration.getSource(), configuration.getTarget()));
        } finally {
            if (URLClassLoader.class.isInstance(classLoader) && configuration.getActionClassLoader() != null) {
                try {
                    URLClassLoader.class.cast(classLoader).close();
                } catch (final IOException e) {
                    configuration.getAsciidoctorConfiguration().error().accept(e.getMessage());
                }
            }
            thread.setContextClassLoader(parentLoader);
        }
    }

    protected void render(final Page page, final Path html,
                        final Asciidoctor asciidoctor, final Options options,
                        final Function<Page, String> template,
                        final boolean withLeftMenuIfConfigured,
                        final Function<String, String> postProcessor) {
        try {
            final Map<String, Object> attrs = new HashMap<>(Map.of("minisite-passthrough", true));
            attrs.putAll(page.attributes);
            final var content = template.apply(new Page(
                    '/' + configuration.getTarget().relativize(html).toString().replace(File.separatorChar, '/'),
                    ofNullable(page.title).orElseGet(this::getTitle),
                    attrs, "" +
                    "        <div class=\"container page-content\">\n" +
                    ofNullable(page.title).map(t -> "" +
                            "            <div class=\"page-header\">\n" +
                            "                <h1>" + t + "</h1>\n" +
                            "            </div>\n")
                            .orElse("") +
                    "            <div class=\"page-content-body\">\n" +
                    renderAdoc(page, asciidoctor, options).trim() + '\n' +
                    "            </div>\n" +
                    "        </div>\n"
            ));
            Files.writeString(html, postProcessor.apply(withLeftMenuIfConfigured || !configuration.isTemplateAddLeftMenu() ? content : dropLeftMenu(content)));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String getIcon(final Page it) {
        return ofNullable(it.attributes.get("minisite-index-icon"))
                .map(String::valueOf)
                .map(i -> i.startsWith("fa") && i.contains(" ") ? i : ("fas fa-" + i))
                .orElse("fas fa-download-alt");
    }

    protected String getTitle(final Page it) {
        return ofNullable(it.attributes.get("minisite-index-title")).map(String::valueOf).orElse(it.title);
    }

    protected String leftMenu(final Map<Page, Path> files) {
        final Path output = configuration.getTarget();
        return "" +
                "        <div class=\"page-navigation-left\">\n" +
                "            <h3>Menu</h3>\n" +
                "            <ul>\n" +
                findIndexPages(files).map(it -> "                " +
                        "<li><a href=\"" + configuration.getSiteBase() + '/' + output.relativize(it.getValue()) + "\">" +
                        "<i class=\"" + getIcon(it.getKey()) + "\"></i> " +
                        getTitle(it.getKey()) + "</a></li>\n")
                        .collect(joining()) +
                "            </ul>\n" +
                "        </div>";
    }

    /**
     * Logic there is to use attributes to find {@code minisite-index} attributes.
     * The value is an integer to sort the pages.
     * Then all these pages (others are ignored) are added on index page.
     *
     * @param htmls    pages.
     * @param template
     * @return the index.html content.
     */
    protected String generateIndex(final Map<Page, Path> htmls, final Function<Page, String> template,
                                 final boolean hasBlog) {
        final Path output = configuration.getTarget();
        String content = (hasBlog ?
                Stream.concat(
                        findIndexPages(htmls),
                        // add blog last for now
                        Stream.of(new AbstractMap.SimpleImmutableEntry<>(
                                new Page(
                                        "/blog/index.html",
                                        "Blog",
                                        Map.of(
                                                "minisite-index-icon", "fa fa-blog",
                                                "minisite-index-title", "Blog",
                                                "minisite-index-description", "Blogging area."),
                                        null),
                                output.resolve("blog/index.html")))) :
                findIndexPages(htmls))
                .map(p -> "" +
                        "                    <div class=\"col-12 col-lg-4 py-3\">\n" +
                        "                        <div class=\"card shadow-sm\">\n" +
                        "                            <div class=\"card-body\">\n" +
                        "                                <h5 class=\"card-title mb-3\">\n" +
                        "                                    <span class=\"theme-icon-holder card-icon-holder mr-2\">\n" +
                        "                                        <i class=\"" +
                        getIcon(p.getKey()) + "\"></i>\n" +
                        "                                    </span>\n" +
                        "                                    <span class=\"card-title-text\">                         " +
                        getTitle(p.getKey()) + "</span>\n" +
                        "                                </h5>\n" +
                        "                                <div class=\"card-text\">\n                                  " +
                        ofNullable(p.getKey().attributes.get("minisite-index-description")).map(String::valueOf).orElse(p.getKey().title) +
                        "\n                                </div>\n" +
                        "                                <a class=\"card-link-mask\" href=\"" + configuration.getSiteBase() + '/' + output.relativize(p.getValue()) + "\"></a>\n" +
                        "                            </div>\n" +
                        "                        </div>\n" +
                        "                    </div>\n" +
                        "")
                .collect(joining("",
                        "    <div class=\"page-content\">\n" +
                                "        <div class=\"container\">\n" +
                                "            <h1 class=\"page-heading mx-auto\">" + getIndexText() + " Documentation</h1>\n" +
                                "            <div class=\"page-intro mx-auto\">" + getIndexSubTitle() + "</div>\n" +
                                "            <div class=\"docs-overview py-5\">\n" +
                                "                <div class=\"row justify-content-center\">\n",
                        "                </div>\n" +
                                "            </div>\n" +
                                "        </div>\n" +
                                "    </div>\n"));

        content = dropLeftMenu(
                template.apply(new Page(
                        "/index.html",
                        ofNullable(configuration.getTitle()).orElse("Index"), Map.of("minisite-passthrough", true), content)));

        // now we must drop the navigation-left/right since we reused the global template - easier than rebuilding a dedicated layout
        return dropRightColumn(content);
    }

    protected String dropRightColumn(final String content) {
        final int navRight = content.indexOf("<div class=\"page-navigation-right\">");
        if (navRight > 0) {
            return content.substring(0, navRight) + content.substring(content.indexOf("</div>", navRight) + "</div>".length());
        }
        return content;
    }

    protected String dropLeftMenu(final String content) {
        return content.replace("<minisite-menu-placeholder/>\n", "");
    }

    protected Stream<Map.Entry<Page, Path>> findIndexPages(final Map<Page, Path> htmls) {
        return htmls.entrySet().stream()
                .filter(p -> p.getKey().attributes.containsKey("minisite-index"))
                .sorted(comparing(p -> Integer.parseInt(String.valueOf(p.getKey().attributes.get("minisite-index")).trim())));
    }

    protected String generateSiteMap(final Map<Page, Path> pages) {
        final String now = LocalDate.now().toString();
        final Path output = configuration.getTarget();
        return pages.entrySet().stream()
                .sorted(comparing(p -> p.getKey().title))
                .map(it -> "" +
                        "    <url>\n" +
                        "        <loc>" + configuration.getSiteBase() + '/' + output.relativize(it.getValue()) + "</loc>\n" +
                        "        <lastmod>" + ofNullable(it.getKey().attributes.get("minisite-lastmod")).map(String::valueOf).orElse(now) + "</lastmod>\n" +
                        "    </url>\n")
                .collect(joining("",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" " +
                                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                                "xsi:schemaLocation=\"http://www.sitemaps.org/schemas/sitemap/0.9 http://www.sitemaps.org/schemas/sitemap/0.9/sitemap.xsd\">\n",
                        "</urlset>\n"));
    }

    public void doRender(final Asciidoctor asciidoctor, final Options options) {
        final var content = configuration.getSource().resolve("content");
        final Map<Page, Path> files = new HashMap<>();
        final Path output = configuration.getTarget();
        final var now = OffsetDateTime.now();
        if (!Files.exists(output)) {
            try {
                Files.createDirectories(output);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        final var pages = findPages(asciidoctor);
        final var template = createTemplate(options, asciidoctor, pages);
        boolean hasBlog = false;
        if (Files.exists(content)) {
            final List<BlogPage> blog = new ArrayList<>();
            pages.forEach(page -> onVisitedFile(page, asciidoctor, options, template, files, now, blog));
            hasBlog = (!blog.isEmpty() && configuration.isGenerateBlog());
            if (hasBlog) {
                generateBlog(blog, asciidoctor, options, template);
            }
        }
        if (configuration.isGenerateIndex()) {
            try {
                Files.write(output.resolve("index.html"), generateIndex(files, template, hasBlog).getBytes(StandardCharsets.UTF_8));
                configuration.getAsciidoctorConfiguration().debug().accept("Generated index.html");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (configuration.isGenerateSiteMap()) { // ignore blog virtual pages since we want to index the content
            try {
                Files.write(output.resolve("sitemap.xml"), generateSiteMap(files).getBytes(StandardCharsets.UTF_8));
                configuration.getAsciidoctorConfiguration().debug().accept("Generated sitemap.xml");
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (configuration.isTemplateAddLeftMenu()) {
            final String leftMenu = leftMenu(files);
            try {
                Files.walkFileTree(output, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        if (file.getFileName().toString().endsWith(".html")) {
                            final List<String> lines = Files.readAllLines(file);
                            final int toReplace = lines.indexOf("<minisite-menu-placeholder/>");
                            if (toReplace >= 0) {
                                configuration.getAsciidoctorConfiguration().debug().accept("Replacing left menu in " + file);
                                lines.set(toReplace, leftMenu);
                                Files.write(file, lines);
                            }
                        }
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (configuration.isUseDefaultAssets()) {
            Stream.of(
                    "yupiik-tools-maven-plugin/minisite/assets/css/theme.css",
                    "yupiik-tools-maven-plugin/minisite/assets/js/minisite.js")
                    .forEach(resource -> {
                        final Path out = output.resolve(resource.substring("yupiik-tools-maven-plugin/minisite/assets/".length()));
                        try (final BufferedReader buffer = new BufferedReader(new InputStreamReader(Thread.currentThread().getContextClassLoader()
                                .getResourceAsStream(resource), StandardCharsets.UTF_8))) {
                            Files.createDirectories(out.getParent());
                            Files.write(out, buffer.lines().collect(joining("\n")).replace("{{base}}", configuration.getSiteBase())
                                    .getBytes(StandardCharsets.UTF_8));
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }

        final Path assets = configuration.getSource().resolve("assets");
        if (Files.exists(assets)) {
            try {
                Files.walkFileTree(assets, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                        final Path out = output.resolve(assets.relativize(file));
                        if (out.getParent() != null) {
                            Files.createDirectories(out.getParent());
                        }
                        Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
                        configuration.getAsciidoctorConfiguration().debug().accept("Copying " + file + " to " + out);
                        return super.visitFile(file, attrs);
                    }
                });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        if (hasSearch()) {
            final var indexer = new IndexService();
            indexer.write(indexer.index(output, configuration.getSiteBase(), path -> {
                final var location = configuration.getTarget().relativize(path).toString().replace(File.pathSeparatorChar, '/');
                final var name = path.getFileName().toString();
                if (location.startsWith("blog/") && (name.startsWith("page-") || name.equals("index.html"))) {
                    return false;
                }
                return true;
            }), output.resolve(configuration.getSearchIndexName()));
        }

        configuration.getAsciidoctorConfiguration().info().accept("Rendered minisite '" + configuration.getSource().getFileName() + "'");
    }

    protected void onVisitedFile(final Page page, final Asciidoctor asciidoctor, final Options options,
                               final Function<Page, String> template, Map<Page, Path> files,
                               final OffsetDateTime now, final List<BlogPage> blog) {
        if (page.attributes.containsKey("minisite-skip")) {
            return;
        }
        final var out = configuration.getTarget().resolve(page.relativePath.substring(1));
        if (out.getParent() != null && !Files.exists(out.getParent())) {
            try {
                Files.createDirectories(out.getParent());
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
        final var isBlog = isBlogPage(page);
        files.put(page, out);
        if (isBlog) {
            final var publishedDate = readPublishedDate(page);
            if (now.isAfter(publishedDate)) {
                blog.add(new BlogPage(page, publishedDate));
            }
        } else {
            render(page, out, asciidoctor, options, template, true, identity());
            configuration.getAsciidoctorConfiguration().debug().accept("Rendered " + page.relativePath + " to " + out);
        }
    }

    protected boolean isBlogPage(final Page page) {
        return page.attributes.keySet().stream().anyMatch(k -> k.startsWith("minisite-blog"));
    }

    protected OffsetDateTime readPublishedDate(final Page page) {
        return ofNullable(page.attributes.get("minisite-blog-published-date"))
                .map(String::valueOf)
                .map(String::trim)
                .map(value -> {
                    switch (value.length()) {
                        case 10: // yyyy-MM-dd HH:mm
                            return LocalDate.parse(value).atTime(LocalTime.MIN).atOffset(ZoneOffset.UTC);
                        default:
                            if (value.length() > 19) { // with offset
                                return OffsetDateTime.parse(value.replace(' ', 'T'));
                            }
                            // yyyy-MM-dd HH:mm[:ss]
                            return LocalDateTime.parse(value.replace(' ', 'T')).atOffset(ZoneOffset.UTC);
                    }
                })
                .orElseGet(OffsetDateTime::now);
    }

    protected void generateBlog(final List<BlogPage> blog, final Asciidoctor asciidoctor, final Options options,
                                final Function<Page, String> template) {
        blog.sort(comparing(p -> p.publishedDate));
        final var baseBlog = configuration.getTarget().resolve("blog");
        try {
            Files.createDirectories(baseBlog);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }

        paginateBlogPages((number, total) -> "Blog Page " + number + "/" + total, "", blog, asciidoctor, options, template, baseBlog);
        paginatePer("category", "categories", blog, asciidoctor, options, template, baseBlog);
        paginatePer("author", "authors", blog, asciidoctor, options, template, baseBlog);

        // render all blog pages
        blog.forEach(bp -> {
            final var out = configuration.getTarget().resolve(bp.page.relativePath.substring(1));
            final var idx = blog.indexOf(bp);
            render(new Page( // add links to other posts
                    bp.page.relativePath,
                    bp.page.title,
                    bp.page.attributes,
                    bp.page.content + "\n" +
                            "\n" +
                            ofNullable(bp.page.attributes.get("minisite-blog-authors"))
                                    .map(String::valueOf)
                                    .map(authors -> Stream.of(authors.split(","))
                                            .map(String::trim)
                                            .filter(c -> !c.isBlank())
                                            .map(author -> "* link:" + configuration.getSiteBase() + "/blog/author/" + toUrlName(author) + "/page-1.html" + "[" + author + "]")
                                            .collect(joining("\n",
                                                    "[role=blog-categories]\n" +
                                                            "From the same author" + (authors.contains(",") ? "s" : "") + ":\n" +
                                                            "\n" +
                                                            "[role=\"blog-links blog-categories\"]\n", "\n\n")))
                                    .orElse("") +
                            ofNullable(bp.page.attributes.get("minisite-blog-categories"))
                                    .map(String::valueOf)
                                    .map(categories -> Stream.of(categories.split(","))
                                            .map(String::trim)
                                            .filter(c -> !c.isBlank())
                                            .map(category -> "* link:" + configuration.getSiteBase() + "/blog/category/" + toUrlName(category) + "/page-1.html" + "[" + toHumanName(category) + "]")
                                            .collect(joining("\n",
                                                    "[role=blog-categories]\n" +
                                                            "In the same categor" + (categories.contains(",") ? "ies" : "y") + ":\n" +
                                                            "\n" +
                                                            "[role=\"blog-links blog-categories\"]\n", "\n\n")))
                                    .orElse("") +
                            "[role=blog-links]\n" +
                            (idx > 0 ? "* link:" + configuration.getSiteBase() + blog.get(idx - 1).page.relativePath + "[Previous,role=\"blog-link-previous\"]\n" : "") +
                            "* link:" + configuration.getSiteBase() + "/blog/index.html[All posts,role=\"blog-link-all\"]\n" +
                            (idx < (blog.size() - 1) ? "* link:" + configuration.getSiteBase() + blog.get(idx + 1).page.relativePath + "[Next,role=\"blog-link-next\"]\n" : "")
            ), out, asciidoctor, options, template, false, this::markPageAsBlog);
            configuration.getAsciidoctorConfiguration().debug().accept("Rendered " + bp.page.relativePath + " to " + out);
        });
    }

    protected boolean paginatePer(final String singular, final String plural, final List<BlogPage> blog,
                                  final Asciidoctor asciidoctor, final Options options, final Function<Page, String> template,
                                  final Path baseBlog) {
        // per category pagination /category/<name>/page-<x>.html
        final var perCriteria = blog.stream()
                .filter(it -> it.page.attributes.containsKey("minisite-blog-" + plural))
                .flatMap(it -> Stream.of(String.valueOf(it.page.attributes.get("minisite-blog-" + plural)).split(","))
                        .map(String::trim)
                        .filter(c -> !c.isBlank())
                        .map(c -> new AbstractMap.SimpleImmutableEntry<>(c, it)))
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
        perCriteria.forEach((key, posts) -> {
            final var humanName = toHumanName(key);
            final var urlName = toUrlName(key);
            paginateBlogPages(
                    (current, total) -> "Blog " + humanName + " (page " + current + "/" + total + ")",
                    singular + "/" + urlName + '/',
                    posts, asciidoctor, options, template, baseBlog);
        });
        if (perCriteria.values().stream().mapToLong(Collection::size).sum() == 0) {
            return false;
        }
        // /category/index.html
        render(
                new Page(
                        '/' + configuration.getTarget().relativize(baseBlog).toString().replace(File.separatorChar, '/') +
                                "/" + singular + "/index.html",
                        "Blog " + plural,
                        Map.of(),
                        "= Blog " + plural + "\n" +
                                "\n" +
                                perCriteria.keySet().stream()
                                        .map(it -> "* link:" + toUrlName(it) + "/page-1.html[" + toHumanName(it) + ']')
                                        .collect(joining("\n"))),
                baseBlog.resolve(singular + "/index.html"), asciidoctor, options, template,
                false,
                this::markPageAsBlog);
        return true;
    }

    protected String toHumanName(final String string) {
        final var out = new StringBuilder()
                .append(Character.toUpperCase(string.charAt(0)));
        for (int i = 1; i < string.length(); i++) {
            final var c = string.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append(' ').append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    protected String toUrlName(final String string) {
        final var out = new StringBuilder()
                .append(Character.toLowerCase(string.charAt(0)));
        for (int i = 1; i < string.length(); i++) {
            final var c = string.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else if (c == ' ') {
                out.append('-');
            } else if (!Character.isJavaIdentifierPart(c)) { // little shortcut for url friendly test
                out.append('-');
            } else {
                out.append(c);
            }
        }
        return out.toString().replace("--", "-");
    }

    protected void paginateBlogPages(final BiFunction<Integer, Integer, String> prefix, final String pageRelativeFolder,
                                   final List<BlogPage> blogPages,
                                   final Asciidoctor asciidoctor,
                                   final Options options,
                                   final Function<Page, String> template,
                                   final Path baseBlog) {
        if (blogPages.isEmpty()) {
            return;
        }
        if (!pageRelativeFolder.isBlank()) {
            try {
                Files.createDirectories(baseBlog.resolve(pageRelativeFolder));
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
        final int pageSize = configuration.getBlogPageSize() <= 0 ? 10 : configuration.getBlogPageSize();
        final var pages = splitByPage(blogPages, pageSize);
        IntStream.rangeClosed(1, pages.size()).forEach(page -> {
            final var output = baseBlog.resolve(pageRelativeFolder + "page-" + page + ".html");
            render(
                    new Page(
                            '/' + configuration.getTarget().relativize(output).toString().replace(File.separatorChar, '/'),
                            prefix.apply(page, pages.size()),
                            Map.of(),
                            "= " + prefix.apply(page, pages.size()) + "\n" +
                                    pages.get(page - 1).stream()
                                            .map(it -> "" +
                                                    "++++\n" +
                                                    "<div class=\"card shadow-sm\">\n" +
                                                    "  <div class=\"card-body\">\n" +
                                                    "      <h5 class=\"card-title mb-3\">\n" +
                                                    "          <span class=\"theme-icon-holder card-icon-holder mr-2\">\n" +
                                                    "              <i class=\"fa fa-blog\"></i>\n" +
                                                    "          </span>\n" +
                                                    "          <span class=\"card-title-text\">" + it.page.title + "</span>\n" +
                                                    "          <span class=\"card-subtitle-text\">" +
                                                    ofNullable(it.page.attributes.get("minisite-blog-authors"))
                                                            .map(String::valueOf)
                                                            .map(a -> "by " + String.join(" and ", a.split(",")) + ", ")
                                                            .orElse("") +
                                                    readPublishedDate(it.page).toLocalDate() + "</span>\n" +
                                                    "      </h5>\n" +
                                                    "      <div class=\"card-text\">\n" +
                                                    "++++\n" +
                                                    "\n" +
                                                    ofNullable(it.page.attributes.get("minisite-blog-summary"))
                                                            .map(String::valueOf)
                                                            .orElse("") +
                                                    "\n" +
                                                    "++++\n" +
                                                    "      </div>\n" +
                                                    "      <a class=\"card-link-mask\" href=\"" + it.page.relativePath + "\"></a>\n" +
                                                    "  </div>\n" +
                                                    "</div>\n" +
                                                    "++++")
                                            .collect(joining("\n", "\n", "\n")) +
                                    "\n" +
                                    "[role=\"blog-links blog-links-page\"]\n" +
                                    (page > 1 ? "* link:" + configuration.getSiteBase() + "/blog/page-" + (page - 1) + ".html[Previous,role=\"blog-link-previous\"]\n" : "") +
                                    "* link:" + configuration.getSiteBase() + "/blog/index.html[All posts,role=\"blog-link-all\"]\n" +
                                    (page < pages.size() ? "* link:" + configuration.getSiteBase() + "/blog/page-" + (page + 1) + ".html[Next,role=\"blog-link-next\"]\n" : "")),
                    output, asciidoctor, options, template, false,
                    this::markPageAsBlog);
        });

        final var indexRedirect = baseBlog.resolve(pageRelativeFolder + "index.html");
        if (!Files.exists(indexRedirect)) {
            try {
                Files.writeString(
                        indexRedirect,
                        "<html><head><meta http-equiv=\"refresh\" content=\"0; URL=page-1.html\" /></head><body></body></html>",
                        StandardOpenOption.CREATE);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    protected String markPageAsBlog(final String html) {
        return html.replace("<body>", "<body class=\"blog\">");
    }

    protected List<List<BlogPage>> splitByPage(final List<BlogPage> sortedPages, final int pageSize) {
        final int totalPages = (int) Math.ceil(sortedPages.size() * 1. / pageSize);
        return IntStream.rangeClosed(1, totalPages)
                .mapToObj(page -> sortedPages.subList(configuration.getBlogPageSize() * (page - 1), Math.min(configuration.getBlogPageSize() * page, sortedPages.size())))
                .collect(toList());
    }

    protected boolean hasSearch() {
        return !"none".equals(configuration.getSearchIndexName()) && configuration.getSearchIndexName() != null;
    }

    public Function<Page, String> createTemplate(final Options options, final Asciidoctor asciidoctor,
                                                 final Collection<Page> pages) {
        final var layout = configuration.getSource().resolve("templates");
        final var hasBlog = pages.stream().anyMatch(this::isBlogPage);
        String prefix = readTemplates(layout, configuration.getTemplatePrefixes())
                .replace("{{blogLink}}", !hasBlog ? "" : "<li class=\"list-inline-item\">" +
                        "<a href=\"" + configuration.getSiteBase() + "/blog/\">" +
                        "<i class=\"fa fa-blog fa-fw\"></i></a></li>")
                .replace("{{search}}", hasSearch() ? "" +
                        "<li class=\"list-inline-item\">" +
                        "<a id=\"search-button\" href=\"#\" data-toggle=\"modal\" data-target=\"#searchModal\">" +
                        "<i data-toggle=\"tooltip\" data-placement=\"top\" title=\"Search\" class=\"fas fa-search\"></i>" +
                        "</a>" +
                        "</li>\n" +
                        "" : "")
                .replace("{{customHead}}", ofNullable(configuration.getCustomHead()).orElse(""))
                .replace("{{customMenu}}", ofNullable(configuration.getCustomMenu()).orElse(""))
                .replace("{{projectVersion}}", configuration.getProjectVersion()) // enables to invalidate browser cache
                .replace("{{logoText}}", getLogoText())
                .replace("{{base}}", configuration.getSiteBase())
                .replace("{{logo}}", ofNullable(configuration.getLogo()).orElse("//www.yupiik.com/img/favicon.png"))
                .replace("{{linkedInCompany}}", ofNullable(configuration.getLinkedInCompany())
                        .orElse("yupiik"));
        final String suffix = readTemplates(layout, configuration.getTemplateSuffixes())
                .replace("{{searchModal}}", hasSearch() ? "" +
                        "<div class=\"modal fade\" id=\"searchModal\" tabindex=\"-1\" role=\"dialog\" aria-labelledby=\"searchModalLabel\" aria-hidden=\"true\">\n" +
                        "  <div class=\"modal-dialog modal-dialog-centered\" role=\"document\">\n" +
                        "    <div class=\"modal-content\">\n" +
                        "      <div class=\"modal-header\">\n" +
                        "        <h5 class=\"modal-title\" id=\"searchModalLabel\">Search</h5>\n" +
                        "        <button type=\"button\" class=\"close\" data-dismiss=\"modal\" aria-label=\"Close\">\n" +
                        "          <span aria-hidden=\"true\">&times;</span>\n" +
                        "        </button>\n" +
                        "      </div>\n" +
                        "      <div class=\"modal-body\">\n" +
                        "        <form onsubmit=\"return false;\" class=\"form-inline\">\n" +
                        "          <div class=\"form-group\">\n" +
                        "            <label for=\"searchInput\"><b>Search: </b></label>\n" +
                        "            <input class=\"form-control\" id=\"searchInput\" placeholder=\"Enter search text and hit enter...\">\n" +
                        "          </div>\n" +
                        "        </form>\n" +
                        "        <div class=\"search-hits\"></div>\n" +
                        "      </div>\n" +
                        "      <div class=\"modal-footer\">\n" +
                        "        <button type=\"button\" class=\"btn btn-primary\" data-dismiss=\"modal\">Close</button>\n" +
                        "      </div>\n" +
                        "    </div>\n" +
                        "  </div>\n" +
                        "</div>" :
                        "")
                .replace("{{copyright}}", ofNullable(configuration.getCopyright())
                        .orElse("Yupiik &copy;"))
                .replace("{{linkedInCompany}}", ofNullable(configuration.getLinkedInCompany())
                        .orElse("yupiik"))
                .replace("{{customScripts}}",
                        ofNullable(configuration.getCustomScripts()).orElse("").trim() + (hasSearch() ?
                                "\n    <script src=\"https://cdnjs.cloudflare.com/ajax/libs/fuse.js/6.4.3/fuse.min.js\" " +
                                        "integrity=\"sha512-neoBxVNv0UMXjoilAYGxfWrSsW6iAVclx7vQKdPJ9Peet1bM5YQjU0aIB8LtH8iNPa+pAyMZprBBw2ZQ/Q1LjQ==\" " +
                                        "crossorigin=\"anonymous\"></script>\n" :
                                "\n"))
                .replace("{{projectVersion}}", configuration.getProjectVersion()) // enables to invalidate browser cache
                .replace("{{base}}", configuration.getSiteBase());
        if (configuration.isTemplateAddLeftMenu()) {
            prefix += "\n<minisite-menu-placeholder/>\n";
        }
        final String prefixRef = prefix;
        return page -> String.join(
                "\n",
                prefixRef
                        .replace("{{title}}", ofNullable(page.title).orElse(configuration.getTitle()))
                        .replace("{{highlightJsCss}}", page.attributes == null || !page.attributes.containsKey("minisite-highlightjs-skip") ?
                                "<link rel=\"stylesheet\" href=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/styles/vs2015.min.css\" integrity=\"sha512-w8aclkBlN3Ha08SMwFKXFJqhSUx2qlvTBFLLelF8sm4xQnlg64qmGB/A6pBIKy0W8Bo51yDMDtQiPLNRq1WMcQ==\" crossorigin=\"anonymous\" />" :
                                "")
                        .replace("{{description}}", ofNullable(page.attributes)
                                .map(a -> a.get("minisite-description"))
                                .map(String::valueOf)
                                .orElseGet(() -> ofNullable(configuration.getDescription()).orElseGet(this::getIndexSubTitle))),
                page.attributes != null && page.attributes.containsKey("minisite-passthrough") ? page.content : renderAdoc(page, asciidoctor, options),
                suffix.replace("{{highlightJs}}", page.attributes != null && page.attributes.containsKey("minisite-highlightjs-skip") ? "" : ("" +
                        "<script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/highlight.min.js\" integrity=\"sha512-DrpaExP2d7RJqNhXB41Q/zzzQrtb6J0zfnXD5XeVEWE8d9Hj54irCLj6dRS3eNepPja7DvKcV+9PnHC7A/g83A==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/java.min.js\" integrity=\"sha512-ku64EPM6PjpseXTZbyRs0ZfXSbVsmLe+XcXxO3tZf1zW2GxZ+aKH9LZWgl/CRI9AFkU3IL7Pc1mzcZiUuvk7FQ==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/bash.min.js\" integrity=\"sha512-Hg0ufGEvn0AuzKMU0psJ1iH238iUN6THh7EI0CfA0n1sd3yu6PYet4SaDMpgzN9L1yQHxfB3yc5ezw3PwolIfA==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/json.min.js\" integrity=\"sha512-37sW1XqaJmseHAGNg4N4Y01u6g2do6LZL8tsziiL5CMXGy04Th65OXROw2jeDeXLo5+4Fsx7pmhEJJw77htBFg==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script src=\"//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/languages/dockerfile.min.js\" integrity=\"sha512-eRNl3ty7GOJPBN53nxLgtSSj2rkYj5/W0Vg0MFQBw8xAoILeT6byOogENHHCRRvHil4pKQ/HbgeJ5DOwQK3SJA==\" crossorigin=\"anonymous\"></script>\n" +
                        "    <script>if (!(window.minisite || {}).skipHighlightJs) { hljs.initHighlightingOnLoad(); }</script>")));
    }

    protected Collection<Page> findPages(final Asciidoctor asciidoctor) {
        try {
            final var content = configuration.getSource().resolve("content");
            final Collection<Page> pages = new ArrayList<>();
            Files.walkFileTree(content, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final String filename = file.getFileName().toString();
                    if (!filename.endsWith(".adoc")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (content.relativize(file).toString().startsWith("_partials")) {
                        return FileVisitResult.CONTINUE;
                    }
                    final String contentString = Files.readString(file);
                    final DocumentHeader header = asciidoctor.readDocumentHeader(contentString);
                    final Path out = ofNullable(header.getAttributes().get("minisite-path"))
                            .map(it -> configuration.getTarget().resolve(it.toString()))
                            .orElseGet(() -> configuration.getTarget().resolve(content.relativize(file)).getParent().resolve(
                                    filename.substring(0, filename.length() - ".adoc".length()) + ".html"));
                    final Page page = new Page(
                            '/' + configuration.getTarget().relativize(out).toString().replace(File.separatorChar, '/'),
                            header.getPageTitle(),
                            header.getAttributes(),
                            contentString);
                    pages.add(page);
                    return super.visitFile(file, attrs);
                }
            });
            return pages;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected String getLogoText() {
        return ofNullable(configuration.getLogoText())
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(configuration.getProjectArtifactId()));
    }

    protected String getIndexText() {
        return ofNullable(configuration.getIndexText())
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElseGet(() -> getLogoText() + " Documentation"));
    }

    protected String getIndexSubTitle() {
        return ofNullable(configuration.getIndexSubTitle())
                .orElseGet(() -> ofNullable(configuration.getProjectName())
                        .map(it -> it.replace("Yupiik ", ""))
                        .orElse(configuration.getProjectArtifactId()));
    }

    protected String getTitle() {
        return ofNullable(configuration.getTitle())
                .orElseGet(() -> "Yupiik " + getLogoText());
    }

    protected String renderAdoc(final Page page, final Asciidoctor asciidoctor, final Options options) {
        final StringWriter writer = new StringWriter();
        try (final StringReader reader = new StringReader(page.content)) {
            asciidoctor.convert(reader, writer, options);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return writer.toString();
    }

    protected String readTemplates(final Path layout, final List<String> templatePrefixes) {
        return templatePrefixes.stream()
                .map(it -> {
                    final Path resolved = layout.resolve(it);
                    if (Files.exists(resolved)) {
                        try {
                            return Files.newBufferedReader(resolved);
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    final InputStream stream = Thread.currentThread().getContextClassLoader()
                            .getResourceAsStream("yupiik-tools-maven-plugin/minisite/" + it);
                    return stream == null ? null : new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                })
                .filter(Objects::nonNull)
                .flatMap(it -> {
                    try (final BufferedReader r = it) { // materialize it to close the stream there
                        return r.lines().collect(toList()).stream();
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(joining("\n"));
    }

    public Options createOptions() {
        final AttributesBuilder attributes = AttributesBuilder.attributes()
                .linkCss(false)
                .dataUri(true)
                .attribute("stem")
                .attribute("source-highlighter", "highlightjs")
                .attribute("highlightjsdir", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.0.3")
                .attribute("highlightjs-theme", "//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.4.1/styles/vs2015.min.css")
                .attribute("imagesdir", "images")
                .attributes(configuration.getAttributes() == null ? Map.of() : configuration.getAttributes());
        if (configuration.getProjectVersion() != null && (configuration.getAttributes() == null || !configuration.getAttributes().containsKey("projectVersion"))) {
            attributes.attribute("projectVersion", configuration.getProjectVersion());
        }
        final OptionsBuilder options = OptionsBuilder.options()
                .safe(SafeMode.UNSAFE)
                .backend("html5")
                .inPlace(false)
                .headerFooter(false)
                .baseDir(configuration.getSource().resolve("content").getParent().toAbsolutePath().normalize().toFile())
                .attributes(attributes);
        if (configuration.getTemplateDirs() != null && !configuration.getTemplateDirs().isEmpty()) {
            options.templateDirs(configuration.getTemplateDirs().toArray(new File[0]));
        }
        return options.get();
    }

    @RequiredArgsConstructor
    public static class BlogPage {
        private final Page page;
        private final OffsetDateTime publishedDate;
    }

    @RequiredArgsConstructor
    public static class Page {
        private final String relativePath;
        private final String title;
        private final Map<String, Object> attributes;
        private final String content;
    }
}