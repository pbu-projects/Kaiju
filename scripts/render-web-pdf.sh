#!/usr/bin/env bash
# Generates PDF using the Chromium-based asciidoctor-pdf (asciidoctor-web-pdf) engine
# Note: The browser-based CSS rendering is experimental and lacks the visual polish of the standard asciidoctorPdf Gradle task.

OUTPUT_DIR="build/docs/asciidoctor-web-pdf"
mkdir -p "$OUTPUT_DIR"

echo "Rendering docs/human-strategy.adoc using Chromium Web-PDF engine..."
npx asciidoctor-pdf docs/human-strategy.adoc -B docs -D "$OUTPUT_DIR" --preserve-html
echo "PDF generated at $OUTPUT_DIR/human-strategy.pdf"
