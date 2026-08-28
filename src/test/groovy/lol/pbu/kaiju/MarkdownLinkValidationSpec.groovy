package lol.pbu.kaiju

import spock.lang.Specification
import spock.lang.Unroll

import java.util.regex.Pattern

class MarkdownLinkValidationSpec extends Specification {

    private static final List<String> IGNORED_DIRS = ['.git', '.gradle', 'build', '.idea', 'node_modules']
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile('(?s)```.*?```|`[^`\n]+`')
    private static final Pattern INLINE_LINK_PATTERN = Pattern.compile('!?\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+["\'][^"\']*["\'])?\\)')
    private static final Pattern REF_DEF_PATTERN = Pattern.compile('(?m)^\\s*\\[([^\\]]+)\\]:\\s*(\\S+)')

    @Unroll
    def "validate all markdown links in #markdownFile.name"() {
        given: "the contents of the markdown file with code blocks stripped"
        String rawContent = markdownFile.text
        String sanitizedContent = CODE_BLOCK_PATTERN.matcher(rawContent).replaceAll('')

        and: "extracted reference definitions"
        Map<String, String> refDefs = [:]
        def refMatcher = REF_DEF_PATTERN.matcher(rawContent)
        while (refMatcher.find()) {
            refDefs[refMatcher.group(1).trim().toLowerCase()] = refMatcher.group(2).trim()
        }

        and: "extracted inline and reference link targets"
        List<String> linkTargets = []

        def inlineMatcher = INLINE_LINK_PATTERN.matcher(sanitizedContent)
        while (inlineMatcher.find()) {
            String target = inlineMatcher.group(2).trim()
            if (target && !target.startsWith("javascript:")) {
                linkTargets << target
            }
        }

        refDefs.values().each { target ->
            if (target && !target.startsWith("javascript:")) {
                linkTargets << target
            }
        }

        expect: "all link targets to be valid and existing"
        List<String> brokenLinks = []

        linkTargets.each { target ->
            if (isHttpOrMailUrl(target)) {
                if (!isValidUrl(target)) {
                    brokenLinks << "Invalid URL syntax: '${target}'"
                }
            } else if (target.startsWith("#")) {
                String anchor = target.substring(1).toLowerCase()
                if (!headingExists(rawContent, anchor)) {
                    brokenLinks << "Missing internal anchor: '${target}'"
                }
            } else {
                String pathPart = target.split('#')[0]
                if (pathPart) {
                    File resolved = new File(markdownFile.parentFile, pathPart)
                    File resolvedFromRoot = new File(".", pathPart)
                    if (!resolved.exists() && !resolvedFromRoot.exists()) {
                        brokenLinks << "Local file not found: '${target}' in ${markdownFile.name} (resolved to '${resolved.path}')"
                    }
                }
            }
        }

        assert brokenLinks.isEmpty() : "Found broken links in ${markdownFile.path}:\n" + brokenLinks.join("\n")

        where:
        markdownFile << findMarkdownFiles()
    }

    private static List<File> findMarkdownFiles() {
        File rootDir = new File(".").canonicalFile
        List<File> result = []
        rootDir.eachFileRecurse { file ->
            if (file.isFile() && file.name.endsWith('.md')) {
                boolean inIgnoredDir = IGNORED_DIRS.any { dir ->
                    file.absolutePath.contains(File.separator + dir + File.separator)
                }
                if (!inIgnoredDir) {
                    result << file
                }
            }
        }
        result.sort { it.path }
    }

    private static boolean isHttpOrMailUrl(String target) {
        target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")
    }

    private static boolean isValidUrl(String target) {
        try {
            URI uri = new URI(target)
            return uri.scheme != null && (target.startsWith("mailto:") || uri.host != null)
        } catch (Exception ignored) {
            return false
        }
    }

    private static boolean headingExists(String content, String anchor) {
        def headingMatcher = (content =~ '(?m)^#+\\s+(.+)$')
        while (headingMatcher.find()) {
            String heading = headingMatcher.group(1).trim()
            String slug = heading.toLowerCase()
                .replaceAll('[^a-z0-9 _-]', '')
                .replaceAll('\\s+', '-')
            if (slug == anchor || heading.equalsIgnoreCase(anchor)) {
                return true
            }
        }
        return false
    }
}
