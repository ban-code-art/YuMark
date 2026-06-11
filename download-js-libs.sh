#!/bin/bash
# Download JavaScript libraries for YuMark
# Place downloaded files in app/src/main/res/raw/

echo "Downloading JS libraries for YuMark..."

# marked.js
echo "Downloading marked.js..."
curl -L -o app/src/main/res/raw/marked.min.js https://cdn.jsdelivr.net/npm/marked@11.0.0/marked.min.js

# KaTeX
echo "Downloading KaTeX..."
curl -L -o app/src/main/res/raw/katex.min.js https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js
curl -L -o app/src/main/res/raw/katex.min.css https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css

# Mermaid
echo "Downloading Mermaid..."
curl -L -o app/src/main/res/raw/mermaid.min.js https://cdn.jsdelivr.net/npm/mermaid@10.6.1/dist/mermaid.min.js

# Prism (core + languages)
echo "Downloading Prism..."
curl -L -o app/src/main/res/raw/prism.js https://cdn.jsdelivr.net/npm/prismjs@1.29.0/prism.min.js
curl -L -o app/src/main/res/raw/prism.css https://cdn.jsdelivr.net/npm/prismjs@1.29.0/themes/prism.min.css

echo "Done! All libraries downloaded."
