#!/usr/bin/env pwsh
# Generates PDF using the Chromium-based asciidoctor-pdf (asciidoctor-web-pdf) engine
# Note: The browser-based CSS rendering is experimental and lacks the visual polish of the standard asciidoctorPdf Gradle task.

$OutputDir = "build/docs/asciidoctor-web-pdf"
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Host "Rendering docs/human-strategy.adoc using Chromium Web-PDF engine..."
npx asciidoctor-pdf docs/human-strategy.adoc -B docs -D $OutputDir --preserve-html
Write-Host "PDF generated at $OutputDir/human-strategy.pdf"
